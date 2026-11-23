package io.github.delokoseni.service.diagnostics;

import io.github.delokoseni.model.DiskExtent;
import io.github.delokoseni.model.DiskInfo;
import io.github.delokoseni.model.PartitionStyle;
import io.github.delokoseni.model.VolumeInfo;
import io.github.delokoseni.model.VolumeInspection;
import io.github.delokoseni.service.disk.DiskAnalysis;
import io.github.delokoseni.service.disk.DiskInventory;
import io.github.delokoseni.service.disk.RawDiskAccessStatus;
import io.github.delokoseni.service.disk.WindowsPartitionInfo;
import io.github.delokoseni.service.disk.WindowsPartitionLayout;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticCopyServiceTest {
    private final DiagnosticCopyService service = new DiagnosticCopyService();

    @Test
    void copiesCompleteSingleDiagnosticWithDeviceAndVolumeContext() {
        var diagnostic = new DiagnosticFinding(DiagnosticSeverity.WARNING, "Физический диск 2",
                "CRC32 массива разделов GPT (Резервная копия) недействительна");
        var volume = volume("\\\\?\\Volume{00000000-0000-0000-0000-000000000002}\\", 2);
        AtomicReference<String> copied = new AtomicReference<>();

        assertTrue(service.copyOne(diagnostic, inventory(volume), volume, copied::set));
        assertTrue(copied.get().contains("Уровень: Предупреждение"));
        assertTrue(copied.get().contains("Устройство: Физический диск 2"));
        assertTrue(copied.get().contains("Модель: Модель диска 2"));
        assertTrue(copied.get().contains(volume.volume().volumeGuidPath()));
        assertTrue(copied.get().contains("Описание: " + diagnostic.message()));
    }

    @Test
    void copiesAllDiagnosticsWithSeverityAndDistinctDiskAssociation() {
        var first = new DiagnosticFinding(DiagnosticSeverity.WARNING, "Физический диск 2",
                "CRC32 массива разделов GPT (Резервная копия) недействительна");
        var second = new DiagnosticFinding(DiagnosticSeverity.UNAVAILABLE, "Физический диск 3",
                "Размер физического сектора недоступен");
        AtomicReference<String> copied = new AtomicReference<>();

        assertTrue(service.copyAll(List.of(first, second), inventory(null), null, copied::set));
        assertTrue(copied.get().contains("Предупреждение"));
        assertTrue(copied.get().contains("Физический диск 2"));
        assertTrue(copied.get().contains("Уровень: Недоступно"));
        assertTrue(copied.get().contains("Физический диск 3"));
        assertTrue(copied.get().contains(second.message()));
        assertFalse(service.copyAll(List.of(), inventory(null), null, copied::set));
    }

    @Test
    void copiesSelectedPartitionWhenWindowsLayoutUniquelyMatchesTheVolumeExtent() {
        var volume = volume("\\\\?\\Volume{00000000-0000-0000-0000-000000000002}\\", 2);
        var disk = new DiskAnalysis(
                new DiskInfo(2, "\\\\.\\PhysicalDrive2", "Модель диска 2", "", "",
                        1_000_000, 512, 0, false, PartitionStyle.MBR),
                Optional.empty(),
                Optional.of(new WindowsPartitionLayout(PartitionStyle.MBR,
                        List.of(new WindowsPartitionInfo(1, 0, 8192, 0x07, "", "")))),
                List.of(volume), List.of(), RawDiskAccessStatus.DISCOVERED, "");
        var inventory = new DiskInventory(List.of(disk), List.of(volume), List.of());
        var diagnostic = new DiagnosticFinding(DiagnosticSeverity.WARNING,
                volume.volume().volumeGuidPath(), "Границы NTFS не совпадают");
        AtomicReference<String> copied = new AtomicReference<>();

        assertTrue(service.copyOne(diagnostic, inventory, volume, copied::set));
        assertTrue(copied.get().contains("Том: Архив (\\\\?\\Volume{"));
        assertTrue(copied.get().contains("Раздел: Раздел 1 (Windows, MBR 0x07)"));
    }

    private static VolumeInspection volume(String path, int diskNumber) {
        return new VolumeInspection(new VolumeInfo(path, List.of(), "NTFS", "Архив", 8192, 4096, 512),
                Optional.empty(), List.of(new DiskExtent(diskNumber, 0, 8192)), Optional.empty(), List.of());
    }

    private static DiskInventory inventory(VolumeInspection volume) {
        List<DiskAnalysis> disks = List.of(disk(2), disk(3));
        return new DiskInventory(disks, volume == null ? List.of() : List.of(volume), List.of());
    }

    private static DiskAnalysis disk(int number) {
        DiskInfo info = new DiskInfo(number, "\\\\.\\PhysicalDrive" + number,
                "Модель диска " + number, "", "", 1_000_000, 512, 0, false, PartitionStyle.GPT);
        return new DiskAnalysis(info, Optional.empty(), Optional.empty(), List.of(), List.of(),
                RawDiskAccessStatus.DISCOVERED, "");
    }
}
