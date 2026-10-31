// Validate the I/O protocol's unsigned addresses before constructing a slice.
// Successful byte counts are returned in a signed 32-bit response word.
use std::ops::Range;

pub(crate) fn destination_range(dst: u64, len: u64, memory_len: usize) -> Option<Range<usize>> {
    if len > i32::MAX as u64 {
        return None;
    }
    let start = usize::try_from(dst).ok()?;
    let length = usize::try_from(len).ok()?;
    let end = start.checked_add(length)?;
    (end <= memory_len).then_some(start..end)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_exact_end_and_empty_end_reads() {
        assert_eq!(destination_range(48, 16, 64), Some(48..64));
        assert_eq!(destination_range(64, 0, 64), Some(64..64));
    }

    #[test]
    fn rejects_reads_past_memory_even_when_empty() {
        assert_eq!(destination_range(48, 17, 64), None);
        assert_eq!(destination_range(65, 0, 64), None);
    }

    #[test]
    fn rejects_wrapping_addresses_before_slice_construction() {
        assert_eq!(destination_range(u64::MAX - 7, 16, 64), None);
        assert_eq!(destination_range(u64::MAX, 1, usize::MAX), None);
    }

    #[test]
    fn response_byte_count_cannot_become_negative() {
        let max = i32::MAX as u64;
        assert_eq!(destination_range(0, max, max as usize), Some(0..max as usize));
        assert_eq!(destination_range(0, max + 1, usize::MAX), None);
    }

    #[test]
    fn accepted_ranges_stay_inside_the_buffer() {
        for memory_len in 0..32 {
            for start in 0..40 {
                for length in 0..40 {
                    let actual = destination_range(start, length, memory_len);
                    assert_eq!(actual.is_some(), start + length <= memory_len as u64);
                    if let Some(range) = actual {
                        let mut memory = vec![0u8; memory_len];
                        memory[range].fill(1);
                        assert_eq!(memory.iter().filter(|&&v| v == 1).count(), length as usize);
                    }
                }
            }
        }
    }
}
