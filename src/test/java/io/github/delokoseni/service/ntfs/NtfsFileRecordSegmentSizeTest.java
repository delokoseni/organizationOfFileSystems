package io.github.delokoseni.service.ntfs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NtfsFileRecordSegmentSizeTest {
    @Test
    void decodesSignedBpbEncodingsForSubClusterAndMultiClusterRecords() {
        assertEquals(1024, NtfsFileRecordSegmentSize.decode(-10, 4096));
        assertEquals(8192, NtfsFileRecordSegmentSize.decode(2, 4096));
    }

    @Test
    void formatsSubClusterSizeAsExactFractionAndValidatesWindowsZeroField() {
        assertEquals("1/4 кластера", NtfsFileRecordSegmentSize.describeClustersPerRecord(1024, 4096));
        NtfsFileRecordSegmentSize.validateWindowsClusterCount(1024, 4096, 0);
        NtfsFileRecordSegmentSize.validateWindowsClusterCount(8192, 4096, 2);
        assertThrows(IllegalArgumentException.class,
                () -> NtfsFileRecordSegmentSize.validateWindowsClusterCount(1024, 4096, 1));
    }

    @Test
    void rejectsInvalidEncodingAndOverflow() {
        assertThrows(IllegalArgumentException.class,
                () -> NtfsFileRecordSegmentSize.decode(0, 4096));
        assertThrows(IllegalArgumentException.class,
                () -> NtfsFileRecordSegmentSize.decode(-63, 4096));
        assertThrows(IllegalArgumentException.class,
                () -> NtfsFileRecordSegmentSize.decode(127, Long.MAX_VALUE));
    }
}
