// muguet - io.rs
// Constantin Clerc (c22dev) - 0.1

use crate::mem_base_len;
use std::collections::HashMap;
use std::fs::File;
use std::os::unix::fs::FileExt;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::time::{Duration, Instant};
use wasmtime::SharedMemory;

static STARTED: AtomicBool = AtomicBool::new(false);
const MAX_OPEN: usize = 1024;

pub fn data_dir() -> PathBuf {
    PathBuf::from(std::env::var("MUGUET_DATA").unwrap_or_default())
}

fn manifest_files() -> Vec<(String, u64)> {
    let text = std::fs::read_to_string(data_dir().join("manifest.json")).expect("manifest.json");
    let v: serde_json::Value = serde_json::from_str(&text).expect("manifest json");
    v["files"]
        .as_array()
        .expect("files")
        .iter()
        .map(|f| (f[0].as_str().unwrap_or("").to_string(), f[1].as_u64().unwrap_or(0)))
        .collect()
}

fn atomic_i32(mem: &SharedMemory, addr: u64) -> &AtomicI32 {
    let (base, len) = mem_base_len(mem);
    assert!((addr as usize) + 4 <= len && addr % 4 == 0);
    unsafe { &*(base.add(addr as usize) as *const AtomicI32) }
}

pub fn start(mem: &SharedMemory, table: u64) {
    if STARTED.swap(true, Ordering::SeqCst) {
        return;
    }
    let mem = mem.clone();
    std::thread::Builder::new()
        .name("io".into())
        .spawn(move || serve_loop(mem, table))
        .expect("spawn io thread");
    eprintln!("[io] native I/O thread started (table=0x{table:x})");
}

fn serve_loop(mem: SharedMemory, table: u64) {
    let files = manifest_files();
    let root = data_dir();
    let worldstream = crate::worldstream::Selection::from_environment();
    eprintln!("{}", worldstream.log_line());
    eprintln!(
        "[worldstream-baseline] phase=0 io=original manifest_file_count={} max_open_handles={} interval_seconds=10",
        files.len(), MAX_OPEN,
    );
    // This is an observation-only baseline. The read results, open-handle LRU
    // and producer/consumer synchronization below are intentionally unchanged.
    let mut last_report = Instant::now();
    let mut last_report_reads = 0u64;
    let mut last_report_bytes = 0u64;
    let mut read_errors = 0u64;
    // Phase 1 bounded diagnostics: no extra file reads or allocations per request.
    let mut slow_reads = 0u64;
    let mut longest_read_us = 0u128;
    let mut max_pending_slots = 0u64;
    let mut last_report_slow = 0u64;
    // Keep the most recently read files open. Eviction scans only happen
    // when the cache is full, never on the common streaming-read path.
    let mut handles: HashMap<i32, (File, u64)> = HashMap::new();
    let mut access_clock: u64 = 0;
    let mut opened: u64 = 0;
    let mut evictions: u64 = 0;
    let mut served: u64 = 0;
    let mut bytes: u64 = 0;
    loop {
        let seen = atomic_i32(&mem, table).load(Ordering::SeqCst);
        let n = atomic_i32(&mem, table + 4).load(Ordering::SeqCst).max(0) as u64;
        // The producer owns request priorities; this worker must not reorder slots
        // without proving the shared-memory protocol supports it.
        let pending = (0..n).filter(|slot| {
            atomic_i32(&mem, table + 64 + 64 * slot).load(Ordering::Relaxed) == 1
        }).count() as u64;
        max_pending_slots = max_pending_slots.max(pending);
        for slot in 0..n {
            let w = table + 64 + 64 * slot;
            if atomic_i32(&mem, w).compare_exchange(1, 3, Ordering::SeqCst, Ordering::SeqCst).is_err() {
                continue;
            }
            let word = |k: u64| atomic_i32(&mem, w + 4 * k).load(Ordering::SeqCst) as u32 as u64;
            let id = word(1) as i32;
            let off = word(2) | (word(3) << 32);
            let dst = word(4) | (word(5) << 32);
            let mut len = word(6);
            let read_started = Instant::now();
            let result: i64 = match files.get(id as usize) {
                None => -1,
                Some((path, size)) => {
                    if off >= *size {
                        0
                    } else {
                        len = len.min(size - off);

                        if !handles.contains_key(&id) {
                            // LRU eviction instead of an arbitrary HashMap key.
                            // This avoids reopening frequently streamed files
                            // during high file-count asset loading.
                            if handles.len() >= MAX_OPEN {
                                if let Some(old) = handles.iter().min_by_key(|(_, (_, stamp))| *stamp).map(|(id, _)| *id) {
                                    handles.remove(&old);
                                    evictions += 1;
                                }
                            }
                            match File::open(root.join(path)) {
                                Ok(f) => { handles.insert(id, (f, access_clock)); opened += 1; }
                                Err(e) => eprintln!("[io] open {path}: {e}"),
                            }
                        }
                        let (base, mlen) = mem_base_len(&mem);
                        let Some((f, last_used)) = handles.get_mut(&id) else {
                            atomic_i32(&mem, w + 28).store(-1, Ordering::SeqCst);
                            atomic_i32(&mem, w).store(2, Ordering::SeqCst);
                            let _ = mem.atomic_notify(w, 1);
                            continue;
                        };
                        access_clock = access_clock.wrapping_add(1);
                        *last_used = access_clock;
                        if (dst + len) as usize > mlen {
                            -1
                        } else {
                            let buf = unsafe { std::slice::from_raw_parts_mut(base.add(dst as usize), len as usize) };
                            match f.read_exact_at(buf, off) {
                                Ok(()) => len as i64,
                                Err(e) => { eprintln!("[io] read {path}@{off}+{len}: {e}"); -1 }
                            }
                        }
                    }
                }
            };
            let read_us = read_started.elapsed().as_micros();
            longest_read_us = longest_read_us.max(read_us);
            if read_us >= 50_000 { slow_reads += 1; }
            served += 1;
            if result < 0 { read_errors += 1; }
            if result > 0 { bytes += result as u64; }
            if served % 2000 == 0 {
                eprintln!("[io] {served} reads, {} MB, {} open handles, {} opens, {} evictions", bytes >> 20, handles.len(), opened, evictions);
            }
            atomic_i32(&mem, w + 28).store(result as i32, Ordering::SeqCst);
            atomic_i32(&mem, w).store(2, Ordering::SeqCst);
            let _ = mem.atomic_notify(w, 1);
        }

        // Phase 0 summary runs only on the already-dedicated I/O worker.
        // It adds no per-file logs, extra disk reads or resident byte cache.
        if last_report.elapsed() >= Duration::from_secs(10) {
            let elapsed_ms = last_report.elapsed().as_millis();
            eprintln!(
                "[worldstream-baseline] phase=0 mode={} interval_ms={} reads={} bytes={} total_reads={} total_bytes={} open_handles={} opens={} handle_evictions={} read_errors={} slow_reads_50ms={} slow_reads_interval={} longest_read_us={} max_pending_slots={}",
                worldstream.active.name(), elapsed_ms,
                served.saturating_sub(last_report_reads),
                bytes.saturating_sub(last_report_bytes),
                served, bytes, handles.len(), opened, evictions, read_errors,
                slow_reads, slow_reads.saturating_sub(last_report_slow), longest_read_us, max_pending_slots,
            );
            last_report = Instant::now();
            last_report_reads = served;
            last_report_bytes = bytes;
            last_report_slow = slow_reads;
            longest_read_us = 0;
            max_pending_slots = 0;
        }
        let _ = mem.atomic_wait32(table, seen as u32, Some(Duration::from_millis(20)));
    }
}
