//! Uniform-buffer reuse is gated by the submission belonging to that slot.
//! Queue depth is fixed at startup; changing it with live submissions is unsafe.
pub struct FrameQueue<T> {
    fences: Vec<Option<T>>,
    slot: usize,
    surface_latency: u32,
}

impl<T> FrameQueue<T> {
    pub fn new(mode: &str) -> Self {
        let (slots, surface_latency) = match mode {
            "low_latency" => (2, 2),
            "minimal" => (1, 1),
            _ => (3, 2),
        };
        Self { fences: (0..slots).map(|_| None).collect(), slot: 0, surface_latency }
    }

    pub fn slots(&self) -> usize { self.fences.len() }
    pub fn slot(&self) -> usize { self.slot }
    pub fn surface_latency(&self) -> u32 { self.surface_latency }

    /// The caller MUST wait on the returned submission before writing the new slot.
    pub fn finish(&mut self, submission: Option<T>) -> Option<T> {
        debug_assert!(self.fences[self.slot].is_none());
        self.fences[self.slot] = submission;
        self.slot = (self.slot + 1) % self.slots();
        self.fences[self.slot].take()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use wgpu::*;

    #[test]
    fn every_slot_returns_its_own_fence_before_reuse_including_empty_frames() {
        for mode in ["original", "low_latency", "minimal", "unknown"] {
            let mut q = FrameQueue::new(mode);
            let mut expected = vec![None; q.slots()];
            for frame in 0..100 {
                let submission = (frame % 4 != 0).then_some(frame);
                expected[q.slot()] = submission;
                let next = (q.slot() + 1) % q.slots();
                assert_eq!(q.finish(submission), expected[next].take());
                assert_eq!(q.slot(), next);
            }
        }
    }

    #[test]
    fn all_depths_allocate_valid_uniform_ranges_and_wait_before_overwrite() {
        let (device, queue) = Device::noop(&DeviceDescriptor::default());
        let scope = device.push_error_scope(ErrorFilter::Validation);
        for mode in ["original", "low_latency", "minimal"] {
            let mut q = FrameQueue::new(mode);
            let buffer = device.create_buffer(&BufferDescriptor {
                label: Some("queue depth test"), size: 256 * q.slots() as u64,
                usage: BufferUsages::UNIFORM | BufferUsages::COPY_DST, mapped_at_creation: false,
            });
            for frame in 0u32..20 {
                queue.write_buffer(&buffer, q.slot() as u64 * 256, &frame.to_le_bytes());
                let submission = queue.submit([]);
                if let Some(index) = q.finish(Some(submission)) {
                    device.poll(PollType::Wait { submission_index: Some(index), timeout: None }).unwrap();
                }
            }
        }
        assert!(pollster::block_on(scope.pop()).is_none());
    }
}
