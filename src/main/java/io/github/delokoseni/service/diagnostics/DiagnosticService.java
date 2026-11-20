package io.github.delokoseni.service.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DiagnosticService {
    public List<DiagnosticFinding> classify(String scope, List<String> messages) {
        List<DiagnosticFinding> findings = new ArrayList<>(messages.size());
        for (String message : messages) {
            findings.add(new DiagnosticFinding(classify(message), scope, message));
        }
        return List.copyOf(findings);
    }

    private static DiagnosticSeverity classify(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("error_not_supported") || lower.contains("error_invalid_function")
                || lower.contains("error_call_not_implemented")) {
            return DiagnosticSeverity.WARNING;
        }
        if (lower.contains("ntfs-specific analysis is unavailable")
                || lower.contains("analysis has not been confirmed")
                || lower.contains("analysis was not confirmed")) {
            return DiagnosticSeverity.INFORMATION;
        }
        if (lower.contains("ntfs_volume_data_buffer")) {
            return DiagnosticSeverity.ERROR;
        }
        if (lower.contains("invalid") || lower.contains("overflow") || lower.contains("cyclic")
                || lower.contains("outside") || lower.contains("overlap") || lower.contains("exceeds")
                || lower.contains("inconsistent") || lower.contains("ambiguous")
                || lower.contains("failed") || lower.contains("could not")
                || lower.contains("mapping problem") || lower.contains("does not fit")
                || lower.contains("empty")) {
            return DiagnosticSeverity.ERROR;
        }
        if (lower.contains("missing") || lower.contains("differs") || lower.contains("unavailable")
                || lower.contains("not found") || lower.contains("not supported")) {
            return DiagnosticSeverity.WARNING;
        }
        return DiagnosticSeverity.INFORMATION;
    }
}
