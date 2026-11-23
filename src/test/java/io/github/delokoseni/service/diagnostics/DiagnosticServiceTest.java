package io.github.delokoseni.service.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiagnosticServiceTest {
    private final DiagnosticService service = new DiagnosticService();

    @Test
    void classifiesStructuralErrorsAndMissingMetadata() {
        List<DiagnosticFinding> findings = service.classify("disk 3", List.of(
                "Invalid GPT header CRC32.",
                "Backup GPT header is missing.",
                "Read completed."));

        assertEquals(List.of(DiagnosticSeverity.WARNING, DiagnosticSeverity.WARNING,
                DiagnosticSeverity.INFORMATION), findings.stream().map(DiagnosticFinding::severity).toList());
        assertEquals("disk 3", findings.getFirst().scope());
    }

    @Test
    void classifiesIntentionallySkippedNtfsAnalysisAsInformation() {
        List<DiagnosticFinding> findings = service.classify("ADATA SE760 (D:)", List.of(
                "NTFS-specific analysis is unavailable for filesystem exFAT.",
                "NTFS analysis has not been confirmed or run.",
                "Analysis was not confirmed; no NTFS query was issued."));

        assertEquals(List.of(DiagnosticSeverity.INFORMATION, DiagnosticSeverity.INFORMATION,
                DiagnosticSeverity.INFORMATION), findings.stream().map(DiagnosticFinding::severity).toList());
    }

    @Test
    void classifiesUnsupportedNtfsQueryAsWarningAndMalformedBufferAsError() {
        List<DiagnosticFinding> findings = service.classify("ADATA SE760 (D:)", List.of(
                "FSCTL_GET_NTFS_VOLUME_DATA failed (Win32 error 50 ERROR_NOT_SUPPORTED)",
                "NTFS_VOLUME_DATA_BUFFER is truncated: bytesReturned=95, required=96"));

        assertEquals(List.of(DiagnosticSeverity.WARNING, DiagnosticSeverity.ERROR),
                findings.stream().map(DiagnosticFinding::severity).toList());
    }

    @Test
    void treatsBackupGptCrcAsWarningAndUnavailablePhysicalSectorAsInformation() {
        List<DiagnosticFinding> findings = service.classify("Физический диск 2", List.of(
                "CRC32 массива разделов GPT (Резервная копия) недействительна",
                "CRC32 заголовка GPT (Основная копия) недействительна",
                "Размер физического сектора недоступен"));

        assertEquals(List.of(DiagnosticSeverity.WARNING, DiagnosticSeverity.WARNING,
                        DiagnosticSeverity.INFORMATION),
                findings.stream().map(DiagnosticFinding::severity).toList());
    }

    @Test
    void treatsGptFailuresAsResidualWarningsWhenAValidMbrIsExplicitlyIdentified() {
        List<DiagnosticFinding> findings = service.classify("Physical disk 2", List.of(
                "Остаточные или несогласованные GPT-метаданные рядом с независимо "
                        + "проверенной таблицей MBR: Нет копии GPT с действительными CRC.",
                "Остаточные или несогласованные GPT-метаданные рядом с независимо "
                        + "проверенной таблицей MBR: CRC32 массива разделов GPT (Резервная копия) недействительна"));

        assertEquals(List.of(DiagnosticSeverity.WARNING, DiagnosticSeverity.WARNING),
                findings.stream().map(DiagnosticFinding::severity).toList());
    }
}
