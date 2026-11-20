package io.github.delokoseni.service.disk;

import io.github.delokoseni.model.DiskInfo;
import io.github.delokoseni.model.DiskExtent;
import io.github.delokoseni.model.DeviceIdentification;
import io.github.delokoseni.model.PartitionStyle;
import io.github.delokoseni.model.VolumeInfo;
import io.github.delokoseni.model.VolumeInspection;
import io.github.delokoseni.service.diagnostics.DiagnosticFinding;
import io.github.delokoseni.service.diagnostics.DiagnosticService;
import io.github.delokoseni.service.parser.PartitionComparisonService;
import io.github.delokoseni.service.parser.PartitionTableAnalysis;
import io.github.delokoseni.service.parser.PartitionTableAnalysisService;
import io.github.delokoseni.service.volume.VolumePartitionMappingService;
import io.github.delokoseni.service.volume.ExternalVolumeCandidate;
import io.github.delokoseni.service.volume.VolumeDiskMappingService;
import io.github.delokoseni.service.volume.WindowsVolumeService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class DiskInventoryService {
    private final DiskEnumerator diskEnumerator;
    private final VolumeEnumerator volumeEnumerator;
    private final ExternalDeviceVerifier externalDeviceVerifier;
    private final DiagnosticService diagnostics = new DiagnosticService();

    public DiskInventoryService() {
        this(new WindowsDiskEnumerator(), new WindowsVolumeService());
    }

    private DiskInventoryService(DiskEnumerator diskEnumerator, WindowsVolumeService volumeService) {
        this(diskEnumerator, volumeService::enumerate, volumeService::verifyExternalAdataD);
    }

    DiskInventoryService(
            DiskEnumerator diskEnumerator,
            VolumeEnumerator volumeEnumerator,
            ExternalDeviceVerifier externalDeviceVerifier) {
        this.diskEnumerator = diskEnumerator;
        this.volumeEnumerator = volumeEnumerator;
        this.externalDeviceVerifier = externalDeviceVerifier;
    }

    public DiskInventory inspect() throws IOException {
        List<DiagnosticFinding> inventoryFindings = new ArrayList<>();
        DiskEnumerationResult enumeration;
        try {
            enumeration = diskEnumerator.enumerate();
        } catch (IOException e) {
            enumeration = new DiskEnumerationResult(List.of(),
                    List.of("Physical disk enumeration failed: " + e.getMessage()));
        }
        List<DiscoveredPhysicalDisk> discoveredDisks = enumeration.disks();
        inventoryFindings.addAll(diagnostics.classify("Physical disk enumeration",
                enumeration.diagnostics()));
        List<VolumeInspection> volumes;
        try {
            volumes = volumeEnumerator.enumerate();
        } catch (IOException e) {
            volumes = List.of();
            inventoryFindings.add(new DiagnosticFinding(
                    io.github.delokoseni.service.diagnostics.DiagnosticSeverity.ERROR,
                    "Volume enumeration", "Volume enumeration failed independently of physical disk "
                    + "discovery: " + e.getMessage()));
        }
        Optional<ExternalVolumeCandidate> adataCandidate = Optional.empty();
        String adataEligibilityMessage = "";
        try {
            adataCandidate = Optional.of(externalDeviceVerifier.verify(volumes));
        } catch (IOException e) {
            adataEligibilityMessage = e.getMessage();
            inventoryFindings.add(new DiagnosticFinding(
                    io.github.delokoseni.service.diagnostics.DiagnosticSeverity.WARNING,
                    "ADATA SE760 (D:) eligibility", adataEligibilityMessage));
        }
        Set<Integer> diskNumbers = diskNumbers(discoveredDisks, volumes);
        List<DiskAnalysis> disks = new ArrayList<>();
        for (int diskNumber : diskNumbers) {
            DiscoveredPhysicalDisk discovered = discoveredDisks.stream()
                    .filter(disk -> disk.diskNumber() == diskNumber)
                    .findFirst()
                    .orElse(new DiscoveredPhysicalDisk(diskNumber,
                            "\\\\.\\PhysicalDrive" + diskNumber, ""));
            List<VolumeInspection> mappedVolumes = mappedVolumes(diskNumber, volumes);
            DiskInfo disk = new DiskInfo(diskNumber, discovered.devicePath(), discovered.model(), "", "",
                    0, 0, 0, false, PartitionStyle.UNKNOWN);
            disks.add(new DiskAnalysis(disk, Optional.empty(), Optional.empty(), mappedVolumes, List.of(),
                    RawDiskAccessStatus.DISCOVERED, "Raw disk access has not been attempted."));
        }
        for (VolumeInspection volume : volumes) {
            if (!volume.diagnostics().isEmpty()) {
                inventoryFindings.addAll(diagnostics.classify(
                        volume.volume().volumeGuidPath(), volume.diagnostics()));
            }
        }
        if (disks.isEmpty()) {
            inventoryFindings.add(new DiagnosticFinding(
                    io.github.delokoseni.service.diagnostics.DiagnosticSeverity.WARNING,
                    "Disk inventory",
                    "No physical disk interfaces or physical-disk volume extents were discovered."));
        }
        List<VolumeInfo> volumeInfo = volumes.stream()
                .map(VolumeInspection::volume)
                .toList();
        Map<String, List<DiskExtent>> extentsByVolumeGuid = new LinkedHashMap<>();
        for (VolumeInspection volume : volumes) {
            extentsByVolumeGuid.put(volume.volume().volumeGuidPath(), volume.diskExtents());
        }
        Map<String, Set<Integer>> disksByVolumeGuid =
                new VolumeDiskMappingService().mapVolumesToDisks(volumeInfo, extentsByVolumeGuid);
        DeviceIdentification adataIdentification =
                new ExternalDeviceIdentificationService().identifyExpectedDevice(
                        disks.stream().map(DiskAnalysis::disk).toList(), volumeInfo, disksByVolumeGuid);
        return new DiskInventory(disks, volumes, inventoryFindings,
                adataCandidate, adataEligibilityMessage, adataIdentification);
    }

    public DiskAnalysis inspectDisk(int diskNumber, DiskInventory inventory) {
        DiskAnalysis discovered = inventory.disks().stream()
                .filter(disk -> disk.disk().diskNumber() == diskNumber)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Disk was not found in current inventory"));
        DiscoveredPhysicalDisk physicalDisk = new DiscoveredPhysicalDisk(
                diskNumber, discovered.disk().devicePath(), discovered.disk().model());
        return inspectOne(physicalDisk, inventory.volumes());
    }

    static Set<Integer> diskNumbers(
            List<DiscoveredPhysicalDisk> discoveredDisks, List<VolumeInspection> volumes) {
        Set<Integer> numbers = new LinkedHashSet<>();
        discoveredDisks.forEach(disk -> numbers.add(disk.diskNumber()));
        for (VolumeInspection volume : volumes) {
            for (DiskExtent extent : volume.diskExtents()) {
                numbers.add(extent.diskNumber());
            }
        }
        return java.util.Collections.unmodifiableSet(numbers);
    }

    private DiskAnalysis inspectOne(
            DiscoveredPhysicalDisk discovered, List<VolumeInspection> allVolumes) {
        int diskNumber = discovered.diskNumber();
        String path = "\\\\.\\PhysicalDrive" + diskNumber;
        List<String> diskMessages = new ArrayList<>();
        List<VolumeInspection> mappedVolumes = mappedVolumes(diskNumber, allVolumes);

        try (WindowsDiskReader reader = WindowsDiskReader.open(diskNumber)) {
            StorageDeviceIdentity identity = null;
            try {
                identity = reader.readStorageDeviceIdentity();
            } catch (IOException e) {
                diskMessages.add("Storage identity unavailable: " + e.getMessage());
            }
            int physicalSectorSize = 0;
            try {
                physicalSectorSize = reader.readPhysicalSectorSize();
            } catch (IOException e) {
                diskMessages.add("Physical sector size unavailable: " + e.getMessage());
            }

            PartitionTableAnalysis partitionTable = null;
            try {
                partitionTable = new PartitionTableAnalysisService().analyze(reader);
                diskMessages.addAll(partitionTable.diagnostics());
            } catch (IOException | RuntimeException e) {
                diskMessages.add("Independent partition analysis failed: " + e.getMessage());
            }

            WindowsPartitionLayout windowsLayout = null;
            try {
                windowsLayout = reader.readWindowsPartitionLayout();
                if (partitionTable != null) {
                    diskMessages.addAll(new PartitionComparisonService().compare(
                            partitionTable.style(), partitionTable.partitions(), windowsLayout));
                }
            } catch (IOException | RuntimeException e) {
                diskMessages.add("Windows partition layout unavailable: " + e.getMessage());
            }
            if (windowsLayout != null) {
                for (VolumeInspection volume : mappedVolumes) {
                    List<io.github.delokoseni.model.DiskExtent> diskExtents = volume.diskExtents().stream()
                            .filter(extent -> extent.diskNumber() == diskNumber)
                            .toList();
                    var mappings = new VolumePartitionMappingService().map(
                            diskExtents, java.util.Map.of(diskNumber, windowsLayout.partitions()));
                    for (var mapping : mappings) {
                        if (!mapping.isUnambiguous()) {
                            diskMessages.add("Volume " + volume.volume().volumeGuidPath()
                                    + " mapping problem: " + mapping.diagnostic() + ".");
                        }
                    }
                }
            }

            PartitionStyle style = partitionTable != null ? partitionTable.style()
                    : windowsLayout != null ? windowsLayout.style() : PartitionStyle.UNKNOWN;
            DiskInfo disk = new DiskInfo(diskNumber, path,
                    identity == null || identity.modelName().isBlank()
                            ? discovered.model() : identity.modelName(),
                    identity == null ? "" : identity.vendor(),
                    identity == null ? "" : identity.serialNumber(),
                    reader.getDiskSize(), reader.getLogicalSectorSize(), physicalSectorSize,
                    identity != null && identity.removableMedia(), style);
            return new DiskAnalysis(disk, Optional.ofNullable(partitionTable),
                    Optional.ofNullable(windowsLayout), mappedVolumes,
                    diagnostics.classify("Physical disk " + diskNumber, diskMessages),
                    RawDiskAccessStatus.READ_ACCESS_AVAILABLE, "Read access available");
        } catch (DiskOpenException e) {
            RawDiskAccessStatus status = RawDiskAccessStatus.fromWin32Error(e.win32Error());
            String message = e.getMessage();
            message = withPrivilegeGuidance(status, message);
            return unavailableAnalysis(discovered, path, mappedVolumes, message, status);
        } catch (DiskNativeException e) {
            RawDiskAccessStatus status = RawDiskAccessStatus.fromWin32Error(e.win32Error());
            String message = withPrivilegeGuidance(status, e.getMessage());
            return unavailableAnalysis(discovered, path, mappedVolumes, message, status);
        } catch (IOException | RuntimeException e) {
            return unavailableAnalysis(discovered, path, mappedVolumes,
                    "Physical disk could not be read with read-only access: " + e.getMessage(),
                    RawDiskAccessStatus.INFORMATION_UNAVAILABLE);
        }
    }

    private static List<VolumeInspection> mappedVolumes(
            int diskNumber, List<VolumeInspection> allVolumes) {
        return allVolumes.stream()
                .filter(volume -> volume.diskExtents().stream()
                        .anyMatch(extent -> extent.diskNumber() == diskNumber))
                .toList();
    }

    private static String withPrivilegeGuidance(RawDiskAccessStatus status, String message) {
        if (status == RawDiskAccessStatus.ACCESS_DENIED) {
            try {
                message += " " + new WindowsPrivilegeService().accessDeniedGuidance();
            } catch (IOException privilegeError) {
                message += ". Administrator status could not be determined: "
                        + privilegeError.getMessage();
            }
        }
        return message;
    }

    static DiskAnalysis unavailableAnalysis(
            DiscoveredPhysicalDisk discovered, String path, List<VolumeInspection> mappedVolumes,
            String message, RawDiskAccessStatus status) {
        DiskInfo disk = new DiskInfo(discovered.diskNumber(), path, discovered.model(), "", "",
                0, 0, 0, false, PartitionStyle.UNKNOWN);
        return new DiskAnalysis(disk, Optional.empty(), Optional.empty(), mappedVolumes,
                new DiagnosticService().classify("Physical disk " + discovered.diskNumber(), List.of(message)),
                status, message);
    }
}
