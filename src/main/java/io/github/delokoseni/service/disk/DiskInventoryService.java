package io.github.delokoseni.service.disk;

import io.github.delokoseni.model.DiskInfo;
import io.github.delokoseni.model.DiskExtent;
import io.github.delokoseni.model.PartitionStyle;
import io.github.delokoseni.model.VolumeInspection;
import io.github.delokoseni.service.diagnostics.DiagnosticFinding;
import io.github.delokoseni.service.diagnostics.DiagnosticService;
import io.github.delokoseni.service.parser.PartitionComparisonService;
import io.github.delokoseni.service.parser.PartitionTableAnalysis;
import io.github.delokoseni.service.parser.PartitionTableAnalysisService;
import io.github.delokoseni.service.volume.VolumePartitionMappingService;
import io.github.delokoseni.service.volume.WindowsVolumeService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class DiskInventoryService {
    private final DiskEnumerator diskEnumerator;
    private final VolumeEnumerator volumeEnumerator;
    private final DiagnosticService diagnostics = new DiagnosticService();

    public DiskInventoryService() {
        this(new WindowsDiskEnumerator(), new WindowsVolumeService());
    }

    private DiskInventoryService(DiskEnumerator diskEnumerator, WindowsVolumeService volumeService) {
        this(diskEnumerator, volumeService::enumerate);
    }

    DiskInventoryService(DiskEnumerator diskEnumerator, VolumeEnumerator volumeEnumerator) {
        this.diskEnumerator = diskEnumerator;
        this.volumeEnumerator = volumeEnumerator;
    }

    public DiskInventory inspect() throws IOException {
        List<DiagnosticFinding> inventoryFindings = new ArrayList<>();
        DiskEnumerationResult enumeration;
        try {
            enumeration = diskEnumerator.enumerate();
        } catch (IOException e) {
            enumeration = new DiskEnumerationResult(List.of(),
                    List.of("Не удалось обнаружить физические диски: " + e.getMessage()));
        }
        List<DiscoveredPhysicalDisk> discoveredDisks = enumeration.disks();
        inventoryFindings.addAll(diagnostics.classify("Обнаружение физических дисков",
                enumeration.diagnostics()));
        List<VolumeInspection> volumes;
        try {
            volumes = volumeEnumerator.enumerate();
        } catch (IOException e) {
            volumes = List.of();
            inventoryFindings.add(new DiagnosticFinding(
                    io.github.delokoseni.service.diagnostics.DiagnosticSeverity.ERROR,
                    "Обнаружение томов", "Не удалось отдельно от обнаружения физических дисков "
                    + "перечислить тома: " + e.getMessage()));
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
                    RawDiskAccessStatus.DISCOVERED, "Чтение диска ещё не выполнялось."));
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
                    "Инвентаризация дисков",
                    "Не обнаружены интерфейсы физических дисков или дисковые области томов."));
        }
        return new DiskInventory(disks, volumes, inventoryFindings);
    }

    public DiskAnalysis inspectDisk(int diskNumber, DiskInventory inventory) {
        DiskAnalysis discovered = inventory.disks().stream()
                .filter(disk -> disk.disk().diskNumber() == diskNumber)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Выбранный диск отсутствует в текущем списке."));
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
                diskMessages.add("Идентификатор устройства хранения недоступен: " + e.getMessage());
            }
            if (!discovered.model().isBlank()) {
                if (identity == null) {
                    throw new IOException("Не удалось повторно подтвердить модель выбранного диска; анализ остановлен.");
                }
                if (!modelsMatch(discovered.model(), identity.modelName())) {
                    throw new IOException("Модель устройства на выбранном номере диска изменилась; "
                            + "анализ остановлен без выбора другого диска.");
                }
            }
            int physicalSectorSize = 0;
            try {
                physicalSectorSize = reader.readPhysicalSectorSize();
            } catch (IOException e) {
                diskMessages.add("Размер физического сектора не удалось получить с устройства: " + e.getMessage());
            }

            PartitionTableAnalysis partitionTable = null;
            try {
                partitionTable = new PartitionTableAnalysisService().analyze(reader);
                diskMessages.addAll(partitionTable.diagnostics());
            } catch (IOException | RuntimeException e) {
                diskMessages.add("Независимый анализ разделов завершился ошибкой: " + e.getMessage());
            }

            WindowsPartitionLayout windowsLayout = null;
            try {
                windowsLayout = reader.readWindowsPartitionLayout();
                if (partitionTable != null) {
                    diskMessages.addAll(new PartitionComparisonService().compare(
                            partitionTable.style(), partitionTable.partitions(), windowsLayout));
                }
            } catch (IOException | RuntimeException e) {
                diskMessages.add("Разметка разделов Windows недоступна: " + e.getMessage());
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
                            diskMessages.add("Проблема сопоставления тома " + volume.volume().volumeGuidPath()
                                    + ": " + mapping.diagnostic() + ".");
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
                    diagnostics.classify("Физический диск " + diskNumber, diskMessages),
                    RawDiskAccessStatus.READ_ACCESS_AVAILABLE, "Доступно чтение");
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
                    "Не удалось прочитать физический диск в режиме только для чтения: " + e.getMessage(),
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
                message += ". Не удалось определить статус администратора: "
                        + privilegeError.getMessage();
            }
        }
        return message;
    }

    static boolean modelsMatch(String discoveredModel, String currentModel) {
        String discovered = normalizeModel(discoveredModel);
        String current = normalizeModel(currentModel);
        String discoveredLower = discovered.toLowerCase(java.util.Locale.ROOT);
        String currentLower = current.toLowerCase(java.util.Locale.ROOT);
        return !discovered.isBlank() && !current.isBlank()
                && (discoveredLower.equals(currentLower)
                || Math.min(discoveredLower.length(), currentLower.length()) >= 6
                && (discoveredLower.contains(currentLower) || currentLower.contains(discoveredLower)));
    }

    private static String normalizeModel(String model) {
        String normalized = model == null ? "" : model.trim();
        String suffix = " SCSI Disk Device";
        if (normalized.regionMatches(true,
                Math.max(0, normalized.length() - suffix.length()), suffix, 0, suffix.length())) {
            normalized = normalized.substring(0, normalized.length() - suffix.length()).trim();
        }
        return normalized;
    }

    static DiskAnalysis unavailableAnalysis(
            DiscoveredPhysicalDisk discovered, String path, List<VolumeInspection> mappedVolumes,
            String message, RawDiskAccessStatus status) {
        DiskInfo disk = new DiskInfo(discovered.diskNumber(), path, discovered.model(), "", "",
                0, 0, 0, false, PartitionStyle.UNKNOWN);
        return new DiskAnalysis(disk, Optional.empty(), Optional.empty(), mappedVolumes,
                new DiagnosticService().classify("Физический диск " + discovered.diskNumber(), List.of(message)),
                status, message);
    }
}
