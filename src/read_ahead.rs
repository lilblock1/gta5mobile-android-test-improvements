//! Kernel page-cache hints for consecutive native reads, with no second byte cache.
use std::fs::File;
use std::ops::Range;
use std::time::{Duration, Instant};

const FIRST: u64 = 64 << 10;
const MAX: u64 = 512 << 10;
const PER_SECOND: u64 = 2 << 20;

#[derive(Default)]
pub struct Sequential {
    end: Option<u64>,
    hinted_end: u64,
    window: u64,
}

impl Sequential {
    pub fn completed(&mut self, offset: u64, length: u64, size: u64) -> Option<Range<u64>> {
        let Some(end) = offset.checked_add(length) else { self.reset(); return None };
        if end > size || length == 0 { self.reset(); return None; }
        let consecutive = self.end == Some(offset) && length >= FIRST;
        self.end = Some(end);
        if !consecutive {
            self.window = FIRST;
            self.hinted_end = end;
            return None;
        }
        let target = end.saturating_add(self.window).min(size);
        let start = end.max(self.hinted_end);
        self.window = (self.window * 2).min(MAX);
        if start >= target { return None; }
        Some(start..target)
    }

    pub fn accepted(&mut self, end: u64) { self.hinted_end = end; }
    pub fn reset(&mut self) { *self = Self::default(); }
}

pub struct Budget { since: Instant, bytes: u64 }
impl Budget {
    pub fn new() -> Self { Self { since: Instant::now(), bytes: 0 } }
    pub fn take(&mut self, requested: Range<u64>, now: Instant) -> Option<Range<u64>> {
        if now.duration_since(self.since) >= Duration::from_secs(1) {
            self.since = now;
            self.bytes = 0;
        }
        let length = requested.end.saturating_sub(requested.start).min(PER_SECOND - self.bytes);
        if length == 0 { return None; }
        self.bytes += length;
        Some(requested.start..requested.start + length)
    }
}

pub fn advise(file: &File, range: Range<u64>) -> std::io::Result<()> {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    {
        use std::os::fd::AsRawFd;
        let offset: libc::off_t = range.start.try_into().map_err(|_| std::io::ErrorKind::InvalidInput)?;
        let length: libc::off_t = range.end.checked_sub(range.start)
            .filter(|n| *n > 0 && *n <= MAX).and_then(|n| n.try_into().ok())
            .ok_or(std::io::ErrorKind::InvalidInput)?;
        let result = unsafe { libc::posix_fadvise(file.as_raw_fd(), offset, length, libc::POSIX_FADV_WILLNEED) };
        if result != 0 { return Err(std::io::Error::from_raw_os_error(result)); }
    }
    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    { let _ = (file, range); }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn sequential_hints_grow_but_are_bounded_and_do_not_repeat_or_cross_eof() {
        let mut s = Sequential::default();
        assert_eq!(s.completed(0, FIRST, 100 * MAX), None);
        let mut maximum = 0;
        for i in 1..100 {
            if let Some(r) = s.completed(i * FIRST, FIRST, 100 * MAX) {
                assert!(r.end - r.start <= MAX);
                assert!(r.start >= maximum);
                maximum = r.end;
                s.accepted(r.end);
            }
        }
        assert_eq!(s.completed(0, FIRST, 3 * FIRST), None);
        assert_eq!(s.completed(FIRST, FIRST, 3 * FIRST), Some(2 * FIRST..3 * FIRST));
        assert_eq!(s.completed(2 * FIRST, FIRST, 3 * FIRST), None);
    }

    #[test]
    fn random_small_failed_and_overflowing_reads_cannot_trigger_hints() {
        let mut s = Sequential::default();
        assert_eq!(s.completed(0, FIRST, MAX), None);
        assert_eq!(s.completed(FIRST + 1, FIRST, MAX), None);
        assert_eq!(s.completed(2 * FIRST + 1, 16, MAX), None);
        s.reset();
        assert_eq!(s.completed(0, FIRST, MAX), None);
        assert_eq!(s.completed(u64::MAX, 2, u64::MAX), None);
    }

    #[test]
    fn budget_limits_all_files_together_and_refills_without_sleeping() {
        let mut b = Budget::new();
        let now = b.since;
        for _ in 0..4 { assert_eq!(b.take(0..MAX, now), Some(0..MAX)); }
        assert_eq!(b.take(0..MAX, now), None);
        assert_eq!(b.take(8..MAX, now + Duration::from_secs(1)), Some(8..MAX));
    }

    #[test]
    #[cfg(target_os = "linux")]
    fn kernel_hints_preserve_real_file_contents_and_positional_reads() {
        use std::os::unix::fs::FileExt;
        let path = std::env::temp_dir().join(format!("nightlane-read-ahead-{}", std::process::id()));
        let data: Vec<u8> = (0..MAX).map(|i| (i % 251) as u8).collect();
        std::fs::write(&path, &data).unwrap();
        let file = File::open(&path).unwrap();
        advise(&file, FIRST..MAX).unwrap();
        let mut actual = vec![0; data.len()];
        file.read_exact_at(&mut actual, 0).unwrap();
        assert_eq!(actual, data);
        assert!(advise(&file, 0..0).is_err());
        std::fs::remove_file(path).unwrap();
    }
}
