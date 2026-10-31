#include "HostInternal.h"
#include "WasmFutex.h"
// Android port: bionic has no pthread_cond_timedwait_relative_np. The condvar is
// created on CLOCK_MONOTONIC by pg_port_cond_init so this relative wait matches
// Apple's semantics exactly - wall-clock changes cannot stretch an engine sleep.
#include "PGPort.h"
#include <pthread.h>
#include <stdio.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <errno.h>
#include <time.h>

// One queue shared by all instances of a module's imported shared memory.
// Per-waiter condition variables let notify wake exactly the requested count.
typedef struct Waiter {
  const void *address;
  bool notified;
  pthread_cond_t condition;
  struct Waiter *next;
} Waiter;

static pthread_mutex_t queueLock = PTHREAD_MUTEX_INITIALIZER;
static Waiter *waiters;

static void *checkedAddress(wasm_rt_shared_memory_t *memory, uint64_t offset,
                            uint32_t width) {
  if (offset % width) wasm_rt_trap(WASM_RT_TRAP_UNALIGNED);
  if (offset > memory->size || memory->size - offset < width)
    wasm_rt_trap(WASM_RT_TRAP_OOB);
  return (uint8_t *)(void *)memory->data + offset;
}

static uint32_t waitFor(wasm_rt_shared_memory_t *memory, uint64_t offset,
                        uint64_t expected, uint64_t timeoutBits, uint32_t width) {
  {
    // The first wait on each address is logged: it shows what the engine and
    // the renderer are waiting for (the graphics ring, a mailbox, a file read).
    static uint64_t seen[16];
    static uint32_t seenCount;
    pthread_mutex_lock(&queueLock);
    bool known = false;
    for (uint32_t i = 0; i < seenCount; i++) if (seen[i] == offset) { known = true; break; }
    if (!known && seenCount < 16) {
      seen[seenCount++] = offset;
      pthread_mutex_unlock(&queueLock);
      char line[128];
      snprintf(line, sizeof line, "Futex wait at 0x%llx for %llu.",
               (unsigned long long)offset, (unsigned long long)expected);
      pg_log(line);
      pthread_mutex_lock(&queueLock);
    }
    pthread_mutex_unlock(&queueLock);
  }
  void *address = checkedAddress(memory, offset, width);
  pthread_mutex_lock(&queueLock);
  uint64_t value = width == 4 ? atomic_load((_Atomic uint32_t *)address)
                              : atomic_load((_Atomic uint64_t *)address);
  if (value != expected) {
    pthread_mutex_unlock(&queueLock);
    return 1;
  }
  int64_t timeout = (int64_t)timeoutBits;
  if (!timeout) {
    pthread_mutex_unlock(&queueLock);
    return 2;
  }
  // The condition variable has CLOCK_MONOTONIC semantics. Its timed-wait
  // argument must be an ABSOLUTE deadline (the old code passed a deadline
  // through a helper that added current uptime AGAIN). Calculate it once so
  // a spurious wakeup cannot extend the original timeout.
  struct timespec deadline = {0};
  if (timeout > 0) {
    clock_gettime(CLOCK_MONOTONIC, &deadline);
    deadline.tv_sec += (time_t)(timeout / 1000000000ll);
    deadline.tv_nsec += (long)(timeout % 1000000000ll);
    if (deadline.tv_nsec >= 1000000000l) {
      ++deadline.tv_sec;
      deadline.tv_nsec -= 1000000000l;
    }
  }
  Waiter waiter = {.address = address, .notified = false, .next = waiters};
  pg_port_cond_init(&waiter.condition);
  waiters = &waiter;
  int result = 0;
  while (!waiter.notified && !result) {
    if (timeout < 0) {
      result = pthread_cond_wait(&waiter.condition, &queueLock);
    } else {
      result = pthread_cond_timedwait(&waiter.condition, &queueLock, &deadline);
    }
  }
  Waiter **link = &waiters;
  while (*link != &waiter) link = &(*link)->next;
  *link = waiter.next;
  bool notified = waiter.notified;
  pthread_mutex_unlock(&queueLock);
  pthread_cond_destroy(&waiter.condition);
  return notified ? 0 : 2;
}

uint32_t pg_atomic_wait32(wasm_rt_shared_memory_t *m, uint64_t a, uint32_t v,
                           uint64_t timeout) { return waitFor(m, a, v, timeout, 4); }
uint32_t pg_atomic_wait64(wasm_rt_shared_memory_t *m, uint64_t a, uint64_t v,
                           uint64_t timeout) { return waitFor(m, a, v, timeout, 8); }

uint32_t pg_atomic_notify(wasm_rt_shared_memory_t *m, uint64_t offset,
                           uint32_t count) {
  void *address = checkedAddress(m, offset, 4);
  uint32_t total = 0;
  pthread_mutex_lock(&queueLock);
  for (Waiter *w = waiters; w && total < count; w = w->next) {
    if (w->address == address && !w->notified) {
      w->notified = true;
      pthread_cond_signal(&w->condition);
      total++;
    }
  }
  pthread_mutex_unlock(&queueLock);
  return total;
}
