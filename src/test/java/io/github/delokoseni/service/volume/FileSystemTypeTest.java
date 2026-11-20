package io.github.delokoseni.service.volume;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemTypeTest {
    @Test
    void identifiesCommonWindowsFilesystemsAndUnknownValues() {
        assertEquals(FileSystemType.NTFS, FileSystemType.fromWindowsName("NTFS"));
        assertEquals(FileSystemType.EXFAT, FileSystemType.fromWindowsName("exFAT"));
        assertEquals(FileSystemType.FAT32, FileSystemType.fromWindowsName("FAT32"));
        assertEquals(FileSystemType.FAT32, FileSystemType.fromWindowsName("FAT"));
        assertEquals(FileSystemType.OTHER, FileSystemType.fromWindowsName("ReFS"));
        assertEquals(FileSystemType.UNKNOWN, FileSystemType.fromWindowsName(""));
        assertEquals(FileSystemType.UNKNOWN, FileSystemType.fromWindowsName(null));
    }

    @Test
    void ntfsDataQueryRequiresBothNtfsFilesystemAndExplicitConfirmation() {
        assertTrue(NtfsAnalysisPolicy.shouldQuery("NTFS", true));
        assertFalse(NtfsAnalysisPolicy.shouldQuery("NTFS", false));
        assertFalse(NtfsAnalysisPolicy.shouldQuery("exFAT", true));
        assertFalse(NtfsAnalysisPolicy.shouldQuery("FAT32", true));
        assertFalse(NtfsAnalysisPolicy.shouldQuery("", true));
    }
}
