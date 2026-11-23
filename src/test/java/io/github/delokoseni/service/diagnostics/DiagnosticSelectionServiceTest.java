package io.github.delokoseni.service.diagnostics;

import io.github.delokoseni.model.DiskExtent;
import io.github.delokoseni.model.DiskInfo;
import io.github.delokoseni.model.PartitionStyle;
import io.github.delokoseni.model.VolumeInfo;
import io.github.delokoseni.model.VolumeInspection;
import io.github.delokoseni.service.disk.DiskAnalysis;
import io.github.delokoseni.service.disk.DiskInventory;
import io.github.delokoseni.service.disk.RawDiskAccessStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticSelectionServiceTest {
    private final DiagnosticSelectionService service = new DiagnosticSelectionService();

    @Test
    void selectedVolumeShowsOnlyItsVolumeAndAssociatedDiskDiagnostics() {
        VolumeInspection adata = volume("ADATA", 1, "D:\\");
        VolumeInspection kingston = volume("Kingston", 2, "E:\\");
        DiagnosticFinding adataDisk = finding("Физический диск 1",
                "Физический сектор недоступен для ADATA");
        DiagnosticFinding kingstonDisk = finding("Физический диск 2",
                "Физический сектор недоступен для Kingston");
        DiagnosticFinding adataVolume = finding(adata.volume().volumeGuidPath(),
                "Диагностика тома ADATA");
        DiagnosticFinding kingstonVolume = finding(kingston.volume().volumeGuidPath(),
                "Диагностика тома Kingston");
        DiagnosticFinding adataPartitionMapping = finding("Физический диск 1",
                "Проблема сопоставления тома " + adata.volume().volumeGuidPath());
        DiagnosticFinding otherVolumeOnSameDisk = finding("Физический диск 1",
                "Проблема сопоставления тома " + kingston.volume().volumeGuidPath());
        DiagnosticFinding global = finding("Инвентаризация дисков", "Общая ошибка инвентаризации");
        DiskInventory inventory = new DiskInventory(
                List.of(disk(1, List.of(adataDisk)), disk(2, List.of(kingstonDisk))),
                List.of(adata, kingston),
                List.of(adataVolume, kingstonVolume, adataPartitionMapping,
                        otherVolumeOnSameDisk, global));

        List<DiagnosticFinding> shown = service.visibleFindings(inventory, adata, List.of(), List.of());

        assertTrue(shown.contains(adataDisk));
        assertTrue(shown.contains(adataVolume));
        assertTrue(shown.contains(adataPartitionMapping));
        assertFalse(shown.contains(kingstonDisk));
        assertFalse(shown.contains(kingstonVolume));
        assertFalse(shown.contains(otherVolumeOnSameDisk));
        assertFalse(shown.contains(global));
    }

    @Test
    void switchingSelectionAndRefreshingDoesNotCarryVolumeDiagnosticsForward() {
        VolumeInspection adata = volume("ADATA", 1, "D:\\");
        VolumeInspection kingston = volume("Kingston", 2, "E:\\");
        DiagnosticFinding adataAnalysis = finding(adata.volume().volumeGuidPath(), "ADATA NTFS result");
        DiagnosticFinding kingstonAnalysis = finding(kingston.volume().volumeGuidPath(), "Kingston NTFS result");
        DiskInventory inventory = new DiskInventory(
                List.of(disk(1, List.of()), disk(2, List.of())),
                List.of(adata, kingston), List.of());

        List<DiagnosticFinding> adataShown = service.visibleFindings(
                inventory, adata, List.of(adataAnalysis), List.of(adataAnalysis, kingstonAnalysis));
        List<DiagnosticFinding> kingstonShown = service.visibleFindings(
                inventory, kingston, List.of(kingstonAnalysis), List.of(adataAnalysis, kingstonAnalysis));
        List<DiagnosticFinding> refreshed = service.visibleFindings(
                inventory, adata, List.of(), List.of());

        assertEquals(List.of(adataAnalysis), adataShown);
        assertEquals(List.of(kingstonAnalysis), kingstonShown);
        assertTrue(refreshed.isEmpty());
    }

    @Test
    void globalDiagnosticsRemainAvailableWithDeviceIdentitiesWhenNoVolumeIsSelected() {
        DiagnosticFinding first = finding("Физический диск 1", "ADATA warning");
        DiagnosticFinding second = finding("Физический диск 2", "Kingston warning");
        DiskInventory inventory = new DiskInventory(
                List.of(disk(1, List.of(first)), disk(2, List.of(second))),
                List.of(), List.of(finding("Обнаружение дисков", "Discovery warning")));

        List<DiagnosticFinding> shown = service.visibleFindings(inventory, null, List.of(), List.of());

        assertTrue(shown.contains(first));
        assertTrue(shown.contains(second));
        assertTrue(shown.contains(inventory.diagnostics().getFirst()));
    }

    private static DiagnosticFinding finding(String scope, String message) {
        return new DiagnosticFinding(DiagnosticSeverity.WARNING, scope, message);
    }

    private static VolumeInspection volume(String label, int diskNumber, String drive) {
        String guid = "\\\\?\\Volume{" + label + "}\\";
        return new VolumeInspection(new VolumeInfo(guid, List.of(drive), "NTFS", label,
                8192, 4096, 512), Optional.empty(),
                List.of(new DiskExtent(diskNumber, 1_048_576L, 8192)),
                Optional.empty(), List.of());
    }

    private static DiskAnalysis disk(int number, List<DiagnosticFinding> findings) {
        DiskInfo info = new DiskInfo(number, "\\\\.\\PhysicalDrive" + number,
                "Disk " + number, "", "", 1_000_000, 512, 0,
                false, PartitionStyle.MBR);
        return new DiskAnalysis(info, Optional.empty(), Optional.empty(), List.of(), findings,
                RawDiskAccessStatus.READ_ACCESS_AVAILABLE, "");
    }
}
