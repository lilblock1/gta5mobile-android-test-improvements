package dev.muguet;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class HeapPolicyTest {
    private static final long GIB = 1024L * 1024 * 1024;

    @Test public void reservesPhysicalMemoryWithoutIncreasingAChosenHeap() {
        assertEquals(3, HeapPolicy.limit(8, 4 * GIB, true));
        assertEquals(4, HeapPolicy.limit(8, 6 * GIB, true));
        assertEquals(5, HeapPolicy.limit(8, 8 * GIB, true));
        assertEquals(8, HeapPolicy.limit(8, 12 * GIB, true));
        assertEquals(3, HeapPolicy.limit(3, 12 * GIB, true));
        assertEquals(4, HeapPolicy.limit(8, (long) (5.7 * GIB), true));
    }

    @Test public void unknownMemoryAndExplicitOptOutKeepTheChosenLimit() {
        assertEquals(5, HeapPolicy.limit(5, 0, true));
        assertEquals(5, HeapPolicy.limit(5, -1, true));
        assertEquals(5, HeapPolicy.limit(5, 6 * GIB, false));
        assertEquals(1, HeapPolicy.limit(-10, 12 * GIB, true));
        assertEquals(8, HeapPolicy.limit(100, Long.MAX_VALUE, true));
    }
}
