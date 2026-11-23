package io.github.delokoseni.service.volume;

import io.github.delokoseni.model.DiskExtent;
import io.github.delokoseni.model.VolumeInfo;
import io.github.delokoseni.model.VolumeInspection;
import io.github.delokoseni.service.disk.WindowsPartitionInfo;
import io.github.delokoseni.service.diagnostics.DiagnosticService;
import io.github.delokoseni.service.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NtfsAnalysisReadinessTest {
    private static final String VOLUME_GUID = "\\\\?\\Volume{00000000-0000-0000-0000-000000000001}\\";

    @Test
    void enablesNtfsAnalysisWithValidVolumeAndNoDiskDiagnostics() {
        VolumeInspection volume = volume(VOLUME_GUID, "NTFS", List.of(new DiskExtent(2, 4096, 8192)));

        assertTrue(NtfsAnalysisReadiness.evaluate(volume, List.of(volume)).available());
    }

    @Test
    void diskGptWarningsDoNotParticipateInNtfsReadiness() {
        VolumeInspection volume = volume(VOLUME_GUID, "NTFS", List.of(new DiskExtent(2, 4096, 8192)));
        var findings = new DiagnosticService().classify("Физический диск 2", List.of(
                "CRC32 массива разделов GPT (Резервная копия) недействительна",
                "Размер физического сектора недоступен"));

        assertTrue(findings.stream().anyMatch(finding -> finding.severity() == DiagnosticSeverity.WARNING));
        assertTrue(findings.stream().anyMatch(finding -> finding.severity() == DiagnosticSeverity.INFORMATION));
        assertTrue(NtfsAnalysisReadiness.evaluate(volume, List.of(volume)).available());
    }

    @Test
    void missingPhysicalSectorSizeDoesNotParticipateInNtfsReadiness() {
        VolumeInspection volume = volume(VOLUME_GUID, "NTFS", List.of(new DiskExtent(2, 4096, 8192)));
        VolumeAnalysisCandidate candidate = new VolumeAnalysisCandidate(volume, 2, "Kingston",
                "", 100_000, 512, OptionalInt.empty(),
                List.of(new WindowsPartitionInfo(1, 0, 100_000, 0x07, "", "")));

        assertTrue(candidate.physicalSectorSize().isEmpty());
        assertTrue(NtfsAnalysisReadiness.evaluate(candidate.volumeInspection(), List.of(volume)).available());
    }

    @Test
    void rejectsUnresolvedPathNonNtfsDisconnectedAndMultidiskVolumesWithReasons() {
        VolumeInspection unresolved = volume("не GUID тома", "NTFS", List.of(new DiskExtent(2, 0, 4096)));
        VolumeInspection exfat = volume(VOLUME_GUID, "exFAT", List.of(new DiskExtent(2, 0, 4096)));
        VolumeInspection multiDisk = volume(VOLUME_GUID, "NTFS",
                List.of(new DiskExtent(2, 0, 4096), new DiskExtent(3, 0, 4096)));
        var unresolvedResult = NtfsAnalysisReadiness.evaluate(unresolved, List.of(unresolved));
        var nonNtfsResult = NtfsAnalysisReadiness.evaluate(exfat, List.of(exfat));
        var disconnectedResult = NtfsAnalysisReadiness.evaluate(
                volume(VOLUME_GUID, "NTFS", List.of(new DiskExtent(2, 0, 4096))),
                List.of(volume("\\\\?\\Volume{00000000-0000-0000-0000-000000000099}\\",
                        "NTFS", List.of(new DiskExtent(9, 0, 4096)))));
        var multiDiskResult = NtfsAnalysisReadiness.evaluate(multiDisk, List.of(multiDisk));

        assertFalse(unresolvedResult.available());
        assertTrue(unresolvedResult.explanation().contains("путь GUID"));
        assertFalse(nonNtfsResult.available());
        assertTrue(nonNtfsResult.explanation().contains("exFAT"));
        assertFalse(disconnectedResult.available());
        assertTrue(disconnectedResult.explanation().contains("больше не обнаружен"));
        assertFalse(multiDiskResult.available());
        assertTrue(multiDiskResult.explanation().contains("несколько физических дисков"));
    }

    private static VolumeInspection volume(String path, String fileSystem, List<DiskExtent> extents) {
        return new VolumeInspection(new VolumeInfo(path, List.of(), fileSystem, "", 8192, 4096, 512),
                Optional.empty(), extents, Optional.empty(), List.of());
    }
}
