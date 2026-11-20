package io.github.delokoseni.service.disk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowsDiskEnumeratorTest {
    @Test
    void extractsNonContiguousDiskNumberFromPnpDeviceObjectName() {
        assertEquals(17, WindowsDiskEnumerator.parseDiskNumber("\\Device\\Harddisk17\\DR17"));
        assertEquals(2, WindowsDiskEnumerator.parseDiskNumber("\\Device\\Harddisk2\\Partition0"));
    }

    @Test
    void rejectsMissingOrInvalidDiskNumber() {
        assertEquals(-1, WindowsDiskEnumerator.parseDiskNumber(null));
        assertEquals(-1, WindowsDiskEnumerator.parseDiskNumber("\\Device\\HarddiskX\\DRX"));
        assertEquals(-1, WindowsDiskEnumerator.parseDiskNumber("\\Device\\CdRom0"));
    }

    @Test
    void retainsKnownDisksAlongsidePartialDiscoveryDiagnostics() {
        var result = new DiskEnumerationResult(
                java.util.List.of(new DiscoveredPhysicalDisk(
                        6, "\\\\.\\PhysicalDrive6", "Disk 6")),
                java.util.List.of("One interface was skipped."));

        assertEquals(6, result.disks().getFirst().diskNumber());
        assertEquals(1, result.diagnostics().size());
    }
}
