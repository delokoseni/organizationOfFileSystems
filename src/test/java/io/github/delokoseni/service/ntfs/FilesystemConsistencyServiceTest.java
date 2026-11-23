package io.github.delokoseni.service.ntfs;

import io.github.delokoseni.model.NtfsMetrics;
import io.github.delokoseni.model.NtfsVolumeData;
import io.github.delokoseni.model.VolumeFileSystemInfo;
import io.github.delokoseni.service.diagnostics.DiagnosticService;
import io.github.delokoseni.service.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemConsistencyServiceTest {
    @Test
    void reportsCapacityDifferencesWithoutTreatingThemAsRepairConditions() {
        NtfsVolumeData ntfs = new NtfsVolumeData(1, 1000, 100, 20, 5,
                512, 4096, 1024, 1, 8192, 2, 4, 10, 20);
        NtfsMetrics metrics = new NtfsCalculationService().calculate(ntfs);
        VolumeFileSystemInfo filesystem = new VolumeFileSystemInfo("NTFS", "", 1,
                255, 0, 400_000, 10_000, 8_000, 8, 512);

        var diagnostics = new FilesystemConsistencyService()
                .validate(512_000, 512_000, filesystem, ntfs, metrics);

        assertTrue(diagnostics.stream().anyMatch(text -> text.contains("меньше размера сопоставленных областей")));
        assertTrue(diagnostics.stream().anyMatch(text -> text.contains("Ёмкость кластеров NTFS меньше")));
    }

    @Test
    void rejectsNegativePartitionOrExtentSizes() {
        NtfsVolumeData ntfs = new NtfsVolumeData(1, 1000, 100, 20, 5,
                512, 4096, 1024, 1, 8192, 2, 4, 10, 20);
        NtfsMetrics metrics = new NtfsCalculationService().calculate(ntfs);
        VolumeFileSystemInfo filesystem = new VolumeFileSystemInfo("NTFS", "", 1,
                255, 0, 400_000, 10_000, 8_000, 8, 512);

        assertThrows(IllegalArgumentException.class, () -> new FilesystemConsistencyService()
                .validate(-1, 500_000, filesystem, ntfs, metrics));
    }

    @Test
    void retainsOneSectorBoundaryMismatchEvenWhenTheTerminalSectorMatches() {
        int sectorSize = 512;
        long extentSectors = 500_084_736L;
        long ntfsSectors = extentSectors - 1;
        long extentBytes = extentSectors * sectorSize;
        long totalClusterBytes = 62_510_591L * 4096;
        NtfsVolumeData ntfs = new NtfsVolumeData(1, ntfsSectors, 62_510_591,
                21_572_958, 1024, sectorSize, 4096, 1024, 0,
                14_417_920, 786_432, 2, 789_952, 837_696);
        NtfsMetrics metrics = new NtfsCalculationService().calculate(ntfs);
        VolumeFileSystemInfo filesystem = new VolumeFileSystemInfo("NTFS", "", 1,
                255, 0, totalClusterBytes, totalClusterBytes / 2, totalClusterBytes / 2, 8, sectorSize);
        NtfsBootSectorInfo bpb = new NtfsBootSectorInfo(
                sectorSize, 8, ntfsSectors, 786_432, 2, -10, 1024);

        var diagnostics = new FilesystemConsistencyService().validate(
                extentBytes, extentBytes, filesystem, ntfs, metrics, bpb, true);
        String boundary = diagnostics.stream()
                .filter(text -> text.contains("Количество секторов NTFS отличается"))
                .findFirst().orElseThrow();

        assertTrue(boundary.contains("Совпадение отдельной копии не доказывает"));
        assertEquals(DiagnosticSeverity.WARNING, new DiagnosticService()
                .classify("NTFS", java.util.List.of(boundary)).getFirst().severity());
    }

    @Test
    void keepsSectorDiscrepancyAsWarningWithoutMatchingTerminalBackupOrBpbEvidence() {
        int sectorSize = 512;
        long extentSectors = 1024;
        NtfsVolumeData ntfs = new NtfsVolumeData(1, 1023, 100, 20, 5,
                sectorSize, 4096, 1024, 0, 8192, 2, 4, 10, 20);
        NtfsMetrics metrics = new NtfsCalculationService().calculate(ntfs);
        VolumeFileSystemInfo filesystem = new VolumeFileSystemInfo(
                "NTFS", "", 1, 255, 0, 409_600, 100_000, 90_000, 8, sectorSize);
        NtfsBootSectorInfo bpb = new NtfsBootSectorInfo(
                sectorSize, 8, 1023, 2, 4, -10, 1024);

        var diagnostics = new FilesystemConsistencyService().validate(
                extentSectors * sectorSize, extentSectors * sectorSize,
                filesystem, ntfs, metrics, bpb, false);
        String boundary = diagnostics.stream()
                .filter(text -> text.contains("Количество секторов NTFS отличается"))
                .findFirst().orElseThrow();

        assertTrue(boundary.contains("LBA тома 1022"));
        org.junit.jupiter.api.Assertions.assertFalse(
                boundary.contains("абсолютный LBA физического диска 1022"),
                "An absolute disk LBA is not fabricated when the extent start is unknown");
        assertTrue(boundary.contains("Копия основной записи на последнем секторе области совпадает=false"));
        assertEquals(DiagnosticSeverity.WARNING, new DiagnosticService()
                .classify("NTFS", java.util.List.of(boundary)).getFirst().severity());
    }

    @Test
    void reportsOneClusterTailWithRelativeAndAbsoluteLbaEvidenceWithoutConfirmingSafety() {
        long ntfsSectors = 121_075_704L;
        long extentSectors = 121_075_712L;
        long bytesPerSector = 512;
        long extentBytes = extentSectors * bytesPerSector;
        NtfsVolumeData ntfs = new NtfsVolumeData(1, ntfsSectors, 15_134_463,
                15_111_960, 0, 512, 4096, 1024, 0,
                100, 200, 1024, 300, 400);
        NtfsMetrics metrics = new NtfsCalculationService().calculate(ntfs);
        VolumeFileSystemInfo filesystem = new VolumeFileSystemInfo(
                "NTFS", "", 1, 255, 0, ntfsSectors * bytesPerSector,
                1_000_000, 900_000, 8, 512);
        NtfsBootSectorInfo bpb = new NtfsBootSectorInfo(512, 8, ntfsSectors,
                100, 200, -10, 1024);

        NtfsBackupBootSectorEvidence evidence = new NtfsBackupBootSectorEvidence(
                121_077_751L, 121_077_759L, true, false,
                java.util.List.of(121_077_752L, 121_077_759L),
                java.util.List.of(121_077_753L, 121_077_754L, 121_077_755L,
                        121_077_756L, 121_077_757L, 121_077_758L),
                java.util.List.of(), java.util.List.of(), true);
        var diagnostics = new FilesystemConsistencyService().validate(
                extentBytes, extentBytes, filesystem, ntfs, metrics, bpb, evidence, 2048);
        String boundary = diagnostics.stream()
                .filter(text -> text.contains("Количество секторов NTFS отличается"))
                .findFirst().orElseThrow();

        assertTrue(boundary.contains("разница (область минус NTFS)=8 логических секторов (4096 байт)"));
        assertTrue(boundary.contains("LBA тома 121075703"));
        assertTrue(boundary.contains("абсолютный LBA физического диска 121077751"));
        assertTrue(boundary.contains("LBA тома 121075711 / абсолютный LBA физического диска 121077759"));
        assertTrue(boundary.contains("Разница равна одному кластеру NTFS"));
        assertTrue(boundary.contains("Причина расхождения не установлена"));
        assertTrue(boundary.contains("Windows сообщает ёмкость файловой системы 61990760448 байт"));
        assertTrue(boundary.contains("Разница области Windows и сообщённой ёмкости файловой системы — 4096 байт"));
        assertTrue(boundary.contains("Совпадающие копии найдены на LBA [121077752, 121077759]"));
        assertTrue(boundary.contains(
                "нулевые абсолютные LBA: [121077753, 121077754, 121077755, 121077756, 121077757, 121077758]"));
        assertTrue(boundary.contains("Хвостовая область прочитана полностью"));
        assertEquals(1, diagnostics.stream()
                .filter(text -> text.contains("Количество секторов NTFS отличается")).count());
        assertTrue(diagnostics.stream().noneMatch(text ->
                text.contains("Ёмкость файловой системы меньше размера сопоставленных областей")
                        || text.contains("Ёмкость кластеров NTFS меньше размера сопоставленных областей")));
        assertEquals(DiagnosticSeverity.WARNING, new DiagnosticService()
                .classify("NTFS", java.util.List.of(boundary)).getFirst().severity());
    }

    @Test
    void reportsMismatchedBackupSectorAndIncompleteBoundaryEvidenceAsUnresolved() {
        long sectors = 1024;
        NtfsVolumeData ntfs = new NtfsVolumeData(1, 1016, 100, 20, 5,
                512, 4096, 1024, 0, 8192, 2, 4, 10, 20);
        NtfsMetrics metrics = new NtfsCalculationService().calculate(ntfs);
        VolumeFileSystemInfo filesystem = new VolumeFileSystemInfo(
                "NTFS", "", 1, 255, 0, 1016L * 512, 100_000, 90_000, 8, 512);
        NtfsBootSectorInfo bpb = new NtfsBootSectorInfo(512, 8, 1016,
                2, 4, -10, 1024);
        NtfsBackupBootSectorEvidence evidence = new NtfsBackupBootSectorEvidence(
                1015, 1023, false, false, java.util.List.of(),
                java.util.List.of(1017L, 1018L), java.util.List.of(1019L),
                java.util.List.of(1020L, 1021L, 1022L, 1023L), false);

        var diagnostics = new FilesystemConsistencyService().validate(
                sectors * 512, sectors * 512, filesystem, ntfs, metrics, bpb, evidence, 0);
        String evidenceText = diagnostics.stream()
                .filter(text -> text.contains("непрочитанные абсолютные LBA:"))
                .findFirst().orElseThrow();

        assertTrue(evidenceText.contains("не удалось прочитать"));
        assertTrue(evidenceText.contains(
                "непрочитанные абсолютные LBA: [1020, 1021, 1022, 1023]"));
        assertTrue(evidenceText.contains("Проверка хвостовой области была ограничена или неполна"));
        assertTrue(evidenceText.contains("не доказывает корректность границы"));
    }

    @Test
    void reportsOneSectorTailInExplicitRelativeAndAbsoluteLbaCoordinates() {
        int sectorSize = 512;
        long extentSectors = 500_084_736L;
        long ntfsSectors = 500_084_735L;
        NtfsVolumeData ntfs = new NtfsVolumeData(1, ntfsSectors, 62_510_591,
                21_572_958, 1024, sectorSize, 4096, 1024, 0,
                14_417_920, 786_432, 2, 789_952, 837_696);
        NtfsMetrics metrics = new NtfsCalculationService().calculate(ntfs);
        long bytes = extentSectors * sectorSize;
        VolumeFileSystemInfo filesystem = new VolumeFileSystemInfo(
                "NTFS", "", 1, 255, 0, 256_043_380_736L,
                10_000_000, 5_000_000, 8, sectorSize);
        NtfsBootSectorInfo bpb = new NtfsBootSectorInfo(
                sectorSize, 8, ntfsSectors, 1, 2, -10, 1024);
        NtfsBackupBootSectorEvidence evidence = new NtfsBackupBootSectorEvidence(
                500_117_502, 500_117_503, true, false,
                java.util.List.of(500_117_503L), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), true);

        String diagnostic = new FilesystemConsistencyService().validate(
                        bytes, bytes, filesystem, ntfs, metrics, bpb, evidence, 32_768)
                .stream().filter(text -> text.contains("Количество секторов NTFS отличается"))
                .findFirst().orElseThrow();

        assertTrue(diagnostic.contains("разница (область минус NTFS)=1 логических секторов (512 байт)"));
        assertTrue(diagnostic.contains("LBA тома 500084734"));
        assertTrue(diagnostic.contains("абсолютный LBA физического диска 500117502"));
        assertTrue(diagnostic.contains(
                "LBA тома 500084735 / абсолютный LBA физического диска 500117503"));
        assertTrue(diagnostic.contains("Windows сообщает ёмкость файловой системы 256043380736 байт"));
        assertTrue(diagnostic.contains("суммарная ёмкость полных кластеров NTFS — 256043380736 байт"));
        assertTrue(diagnostic.contains("Разница области Windows и сообщённой ёмкости файловой системы — 4096 байт"));
        assertTrue(diagnostic.contains("Причина расхождения не установлена"));
    }
}
