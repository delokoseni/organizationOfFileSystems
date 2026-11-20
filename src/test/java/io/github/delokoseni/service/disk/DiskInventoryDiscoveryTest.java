package io.github.delokoseni.service.disk;

import io.github.delokoseni.model.DiskExtent;
import io.github.delokoseni.model.DeviceIdentification;
import io.github.delokoseni.model.VolumeInfo;
import io.github.delokoseni.model.VolumeInspection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskInventoryDiscoveryTest {
    @Test
    void preservesDiscoveredDiskWhenRawReadIsDeniedAndCombinesAllDisks() {
        var denied = new DiscoveredPhysicalDisk(8, "\\\\.\\PhysicalDrive8", "External SSD");
        var volume = new VolumeInspection(new VolumeInfo("\\\\?\\Volume{sample}\\", List.of("D:\\"),
                "NTFS", "", 4096, 1024, 4096), Optional.empty(),
                List.of(new DiskExtent(3, 0, 4096)), Optional.empty(), List.of());

        DiskAnalysis unavailable = DiskInventoryService.unavailableAnalysis(
                denied, denied.devicePath(), List.of(), "Win32 error 5", RawDiskAccessStatus.ACCESS_DENIED);

        assertEquals(RawDiskAccessStatus.ACCESS_DENIED, unavailable.accessStatus());
        assertEquals("External SSD", unavailable.disk().model());
        assertEquals(8, unavailable.disk().diskNumber());
        assertTrue(unavailable.partitionTable().isEmpty());
        assertEquals(Set.of(3, 8), DiskInventoryService.diskNumbers(List.of(denied), List.of(volume)));
    }

    @Test
    void mapsWindowsErrorsToSeparateAvailabilityStates() {
        assertEquals(RawDiskAccessStatus.ACCESS_DENIED, RawDiskAccessStatus.fromWin32Error(5));
        assertEquals(RawDiskAccessStatus.DEVICE_DISCONNECTED, RawDiskAccessStatus.fromWin32Error(6));
        assertEquals(RawDiskAccessStatus.INFORMATION_UNAVAILABLE, RawDiskAccessStatus.fromWin32Error(87));
    }

    @Test
    void retainsCreateFileParametersInAccessFailure() {
        DiskOpenException failure = new DiskOpenException(
                "\\\\.\\PhysicalDrive12", 0x80000000, 3, 3, 0, 5);

        assertEquals(5, failure.win32Error());
        assertEquals(0x80000000, failure.desiredAccess());
        assertTrue(failure.getMessage().contains("PhysicalDrive12"));
        assertTrue(failure.getMessage().contains("shareMode=0x3"));
    }

    @Test
    void discoversEveryPhysicalDiskBeforeAnyRawReadAttempt() throws Exception {
        List<DiscoveredPhysicalDisk> discovered = List.of(
                new DiscoveredPhysicalDisk(0, "\\\\.\\PhysicalDrive0", "System disk"),
                new DiscoveredPhysicalDisk(3, "\\\\.\\PhysicalDrive3", "ADATA SE760"),
                new DiscoveredPhysicalDisk(9, "\\\\.\\PhysicalDrive9", "Internal disk"));
        DiskInventoryService service = new DiskInventoryService(
                () -> new DiskEnumerationResult(discovered, List.of()),
                List::of,
                ignored -> { throw new IOException("D: is unavailable"); });

        var inventory = service.inspect();

        assertEquals(List.of(0, 3, 9),
                inventory.disks().stream().map(disk -> disk.disk().diskNumber()).sorted().toList());
        assertTrue(inventory.disks().stream()
                .allMatch(disk -> disk.accessStatus() == RawDiskAccessStatus.DISCOVERED));
        assertTrue(inventory.adataCandidate().isEmpty());
    }

    @Test
    void volumeEnumerationFailureDoesNotErasePhysicalDiskDiscovery() throws Exception {
        List<DiscoveredPhysicalDisk> discovered = List.of(
                new DiscoveredPhysicalDisk(4, "\\\\.\\PhysicalDrive4", "Disk 4"));
        DiskInventoryService service = new DiskInventoryService(
                () -> new DiskEnumerationResult(discovered, List.of()),
                () -> { throw new IOException("volume access denied"); },
                ignored -> { throw new IOException("D: unavailable"); });

        var inventory = service.inspect();

        assertEquals(1, inventory.disks().size());
        assertEquals(4, inventory.disks().getFirst().disk().diskNumber());
        assertTrue(inventory.diagnostics().stream()
                .anyMatch(finding -> finding.scope().equals("Volume enumeration")));
    }

    @Test
    void physicalEnumerationFailureDoesNotEraseVolumeExtentDiscovery() throws Exception {
        var volume = new VolumeInspection(new VolumeInfo("\\\\?\\Volume{sample}\\", List.of("D:\\"),
                "NTFS", "", 4096, 1024, 4096), Optional.empty(),
                List.of(new DiskExtent(6, 0, 4096)), Optional.empty(), List.of());
        DiskInventoryService service = new DiskInventoryService(
                () -> { throw new IOException("SetupAPI unavailable"); },
                () -> List.of(volume),
                ignored -> { throw new IOException("D: identity unavailable"); });

        var inventory = service.inspect();

        assertEquals(1, inventory.volumes().size());
        assertEquals(List.of(6), inventory.disks().stream()
                .map(disk -> disk.disk().diskNumber()).toList());
        assertTrue(inventory.diagnostics().stream()
                .anyMatch(finding -> finding.scope().equals("Physical disk enumeration")
                        && finding.message().contains("SetupAPI unavailable")));
    }

    @Test
    void identifiesPreferredDeviceFromPnpModelAndDMappingWhenRawReadIsUnavailable() throws Exception {
        var adata = new DiscoveredPhysicalDisk(
                3, "\\\\.\\PhysicalDrive3", "ADATA SE760 SCSI Disk Device");
        var volume = new VolumeInspection(new VolumeInfo("\\\\?\\Volume{d}\\", List.of("D:\\"),
                "NTFS", "", 4096, 1024, 4096), Optional.empty(),
                List.of(new DiskExtent(3, 0, 4096)), Optional.empty(), List.of());
        DiskInventoryService service = new DiskInventoryService(
                () -> new DiskEnumerationResult(List.of(adata), List.of()),
                () -> List.of(volume),
                ignored -> { throw new IOException("raw read denied"); });

        var inventory = service.inspect();

        assertTrue(inventory.adataCandidate().isEmpty());
        assertEquals(DeviceIdentification.Status.IDENTIFIED, inventory.adataIdentification().status());
        assertEquals(3, inventory.adataIdentification().disk().orElseThrow().diskNumber());
    }

    @Test
    void inaccessibleDiskDoesNotHideOtherAccessibleDisks() {
        DiskAnalysis denied = DiskInventoryService.unavailableAnalysis(
                new DiscoveredPhysicalDisk(2, "\\\\.\\PhysicalDrive2", "Denied disk"),
                "\\\\.\\PhysicalDrive2", List.of(), "Win32 error 5", RawDiskAccessStatus.ACCESS_DENIED);
        DiskAnalysis accessible = new DiskAnalysis(
                new io.github.delokoseni.model.DiskInfo(7, "\\\\.\\PhysicalDrive7", "Readable disk", "", "",
                        1024, 512, 512, false, io.github.delokoseni.model.PartitionStyle.RAW),
                Optional.empty(), Optional.empty(), List.of(), List.of(),
                RawDiskAccessStatus.READ_ACCESS_AVAILABLE, "Read access available");

        List<DiskAnalysis> displayed = List.of(denied, accessible);

        assertEquals(2, displayed.size());
        assertEquals(Set.of(2, 7), displayed.stream()
                .map(disk -> disk.disk().diskNumber()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(displayed.stream().anyMatch(disk ->
                disk.accessStatus() == RawDiskAccessStatus.ACCESS_DENIED));
        assertTrue(displayed.stream().anyMatch(disk ->
                disk.accessStatus() == RawDiskAccessStatus.READ_ACCESS_AVAILABLE));
    }
}
