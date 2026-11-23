package io.github.delokoseni.service.diagnostics;

import io.github.delokoseni.model.DiskExtent;
import io.github.delokoseni.model.VolumeInspection;
import io.github.delokoseni.service.disk.DiskAnalysis;
import io.github.delokoseni.service.disk.DiskInventory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DiagnosticSelectionService {
    private static final Pattern DISK_SCOPE =
            Pattern.compile("^(?:Физический диск|Physical disk)\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

    public List<DiagnosticFinding> visibleFindings(
            DiskInventory inventory,
            VolumeInspection selectedVolume,
            List<DiagnosticFinding> selectedVolumeFindings,
            List<DiagnosticFinding> allVolumeFindings) {
        List<DiagnosticFinding> all = new ArrayList<>(inventory.diagnostics());
        inventory.disks().forEach(disk -> all.addAll(disk.diagnostics()));
        all.addAll(allVolumeFindings);
        if (selectedVolume == null) {
            return List.copyOf(new LinkedHashSet<>(all));
        }

        String selectedGuid = selectedVolume.volume().volumeGuidPath();
        Set<Integer> selectedDisks = selectedVolume.diskExtents().stream()
                .map(DiskExtent::diskNumber)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<DiagnosticFinding> selected = all.stream()
                .filter(finding -> relevantToSelection(
                        finding, inventory, selectedGuid, selectedDisks))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        selected.addAll(selectedVolumeFindings);
        return List.copyOf(new LinkedHashSet<>(selected));
    }

    private static boolean relevantToSelection(
            DiagnosticFinding finding,
            DiskInventory inventory,
            String selectedGuid,
            Set<Integer> selectedDisks) {
        if (!selectedGuid.isBlank()
                && finding.scope().regionMatches(true, 0, selectedGuid, 0, selectedGuid.length())) {
            return true;
        }
        Matcher diskMatcher = DISK_SCOPE.matcher(finding.scope());
        if (!diskMatcher.find()) {
            return false;
        }
        int diskNumber;
        try {
            diskNumber = Integer.parseInt(diskMatcher.group(1));
        } catch (NumberFormatException e) {
            return false;
        }
        if (!selectedDisks.contains(diskNumber)
                || findingReferencesAnotherVolume(finding, inventory, selectedGuid)) {
            return false;
        }
        return true;
    }

    private static boolean findingReferencesAnotherVolume(
            DiagnosticFinding finding, DiskInventory inventory, String selectedGuid) {
        return inventory.volumes().stream()
                .map(volume -> volume.volume().volumeGuidPath())
                .filter(guid -> !guid.isBlank() && !guid.equalsIgnoreCase(selectedGuid))
                .anyMatch(guid -> containsIgnoreCase(finding.message(), guid));
    }

    private static boolean containsIgnoreCase(String text, String value) {
        return text.toLowerCase(java.util.Locale.ROOT)
                .contains(value.toLowerCase(java.util.Locale.ROOT));
    }
}
