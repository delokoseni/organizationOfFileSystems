package io.github.delokoseni.service.diagnostics;

import io.github.delokoseni.model.DiskExtent;
import io.github.delokoseni.model.PartitionStyle;
import io.github.delokoseni.model.VolumeInspection;
import io.github.delokoseni.service.disk.DiskAnalysis;
import io.github.delokoseni.service.disk.DiskInventory;
import io.github.delokoseni.service.disk.WindowsPartitionLayout;
import io.github.delokoseni.service.disk.WindowsPartitionInfo;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DiagnosticTextFormatter {
    private static final Pattern DISK_SCOPE = Pattern.compile("^Физический диск\\s+(\\d+)");

    public String format(
            DiagnosticFinding finding, DiskInventory inventory, VolumeInspection selectedVolume) {
        Optional<Integer> diskNumber = diskNumber(finding, inventory, selectedVolume);
        String model = diskNumber.flatMap(number -> inventory.disks().stream()
                        .filter(disk -> disk.disk().diskNumber() == number)
                        .map(DiskAnalysis::disk)
                        .map(disk -> disk.model().isBlank() ? null : disk.model())
                        .filter(java.util.Objects::nonNull)
                        .findFirst())
                .orElse("недоступна");
        String volume = associatedVolume(finding, inventory, selectedVolume, diskNumber);
        String partition = associatedPartition(finding, inventory, selectedVolume, diskNumber);
        String problem = problemName(finding.message());
        return "Уровень: " + severityName(finding.severity())
                + "\nУстройство: " + diskNumber.map(number -> "Физический диск " + number)
                .orElse("не установлено (" + fallback(finding.scope()) + ")")
                + "\nМодель: " + model
                + "\nТом: " + volume
                + "\nРаздел: " + partition
                + "\nПроблема: " + problem
                + "\nОписание: " + finding.message();
    }

    public String scopeLabel(
            DiagnosticFinding finding, DiskInventory inventory, VolumeInspection selectedVolume) {
        Optional<VolumeInspection> scopedVolume = inventory.volumes().stream()
                .filter(item -> finding.scope().regionMatches(true, 0,
                        item.volume().volumeGuidPath(), 0, item.volume().volumeGuidPath().length()))
                .findFirst();
        if (scopedVolume.isPresent()) {
            Optional<Integer> volumeDisk = singleDisk(scopedVolume.orElseThrow());
            return "Том " + volumeName(scopedVolume.orElseThrow()) + " — "
                    + volumeDisk.map(number -> diskDisplayName(number, inventory))
                    .orElse("физический диск не установлен");
        }
        Optional<Integer> diskNumber = diskNumber(finding, inventory, selectedVolume);
        if (diskNumber.isPresent()) {
            int number = diskNumber.orElseThrow();
            String selected = selectedVolume != null && selectedVolume.diskExtents().stream()
                    .anyMatch(extent -> extent.diskNumber() == number)
                    ? "; выбранный том: " + volumeName(selectedVolume) : "";
            return diskDisplayName(number, inventory) + selected;
        }
        return "Устройство не установлено — " + fallback(finding.scope());
    }

    private static String diskDisplayName(int number, DiskInventory inventory) {
        String model = inventory.disks().stream()
                .filter(disk -> disk.disk().diskNumber() == number)
                .map(DiskAnalysis::disk)
                .map(disk -> disk.model().isBlank() ? "" : disk.model())
                .findFirst().orElse("");
        return "физический диск " + number + (model.isBlank() ? "" : " — " + model);
    }

    private static Optional<Integer> diskNumber(
            DiagnosticFinding finding, DiskInventory inventory, VolumeInspection selectedVolume) {
        Matcher matcher = DISK_SCOPE.matcher(finding.scope());
        if (matcher.find()) {
            try {
                return Optional.of(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        Optional<VolumeInspection> scopedVolume = inventory.volumes().stream()
                .filter(volume -> finding.scope().regionMatches(true, 0,
                        volume.volume().volumeGuidPath(), 0, volume.volume().volumeGuidPath().length()))
                .findFirst();
        if (scopedVolume.isPresent()) {
            return singleDisk(scopedVolume.orElseThrow());
        }
        if (selectedVolume != null && !selectedVolume.diskExtents().isEmpty()
                && selectedVolume.diskExtents().stream().allMatch(extent ->
                finding.scope().contains("диск " + extent.diskNumber()))) {
            return singleDisk(selectedVolume);
        }
        return Optional.empty();
    }

    private static Optional<Integer> singleDisk(VolumeInspection inspection) {
        List<Integer> numbers = inspection.diskExtents().stream()
                .map(DiskExtent::diskNumber).distinct().toList();
        return numbers.size() == 1 ? Optional.of(numbers.getFirst()) : Optional.empty();
    }

    private static String associatedVolume(
            DiagnosticFinding finding, DiskInventory inventory, VolumeInspection selectedVolume,
            Optional<Integer> diskNumber) {
        Optional<VolumeInspection> scopedVolume = inventory.volumes().stream()
                .filter(volume -> finding.scope().regionMatches(true, 0,
                        volume.volume().volumeGuidPath(), 0, volume.volume().volumeGuidPath().length()))
                .findFirst();
        if (scopedVolume.isPresent()) {
            return volumeName(scopedVolume.orElseThrow());
        }
        if (selectedVolume != null && diskNumber.isPresent()
                && selectedVolume.diskExtents().stream()
                .anyMatch(extent -> extent.diskNumber() == diskNumber.orElseThrow())) {
            return volumeName(selectedVolume) + " (выбранный том на этом диске)";
        }
        return "не установлено";
    }

    private static String associatedPartition(
            DiagnosticFinding finding, DiskInventory inventory, VolumeInspection selectedVolume,
            Optional<Integer> diskNumber) {
        Optional<VolumeInspection> volume = inventory.volumes().stream()
                .filter(item -> finding.scope().regionMatches(true, 0,
                        item.volume().volumeGuidPath(), 0, item.volume().volumeGuidPath().length()))
                .findFirst();
        if (volume.isEmpty() && selectedVolume != null && diskNumber.isPresent()
                && selectedVolume.diskExtents().stream()
                .anyMatch(extent -> extent.diskNumber() == diskNumber.orElseThrow())) {
            volume = Optional.of(selectedVolume);
        }
        if (volume.isEmpty() || volume.orElseThrow().diskExtents().size() != 1 || diskNumber.isEmpty()) {
            return "не установлен или не сопоставлен однозначно";
        }
        DiskExtent extent = volume.orElseThrow().diskExtents().getFirst();
        List<WindowsPartitionLayout> layouts = inventory.disks().stream()
                .filter(disk -> disk.disk().diskNumber() == diskNumber.orElseThrow())
                .flatMap(disk -> disk.windowsLayout().stream())
                .toList();
        List<WindowsPartitionInfo> matches = layouts.stream()
                .flatMap(layout -> layout.partitions().stream())
                .filter(partition -> partition.startingOffsetBytes() == extent.startingOffsetBytes()
                        && partition.lengthBytes() == extent.extentLengthBytes())
                .toList();
        if (matches.size() != 1 || layouts.size() != 1) {
            return "не установлен или не сопоставлен однозначно";
        }
        WindowsPartitionInfo partition = matches.getFirst();
        String style = layouts.getFirst().style().displayName();
        String identifier = layouts.getFirst().style() == PartitionStyle.MBR
                ? "MBR " + String.format(Locale.ROOT, "0x%02X", partition.mbrType())
                : style;
        return "Раздел " + partition.partitionNumber() + " (Windows, " + identifier + ")";
    }

    private static String volumeName(VolumeInspection inspection) {
        String guid = inspection.volume().volumeGuidPath();
        String label = inspection.volume().label();
        return label == null || label.isBlank() ? guid : label + " (" + guid + ")";
    }

    private static String problemName(String message) {
        if (message.contains("CRC32 массива разделов GPT")) {
            return "CRC32 массива разделов GPT";
        }
        if (message.contains("Размер физического сектора")) {
            return "Размер физического сектора недоступен";
        }
        return "Диагностическое сообщение";
    }

    private static String severityName(DiagnosticSeverity severity) {
        return switch (severity) {
            case ERROR -> "Ошибка";
            case WARNING -> "Предупреждение";
            case INFORMATION -> "Информация";
            case UNAVAILABLE -> "Недоступно";
        };
    }

    private static String fallback(String value) {
        return value == null || value.isBlank() ? "не установлено" : value;
    }
}
