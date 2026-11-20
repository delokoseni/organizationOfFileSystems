package io.github.delokoseni.service.diagnostics;

import java.util.Objects;

public record DiagnosticFinding(
        DiagnosticSeverity severity,
        String scope,
        String message) {
    public DiagnosticFinding {
        severity = Objects.requireNonNull(severity);
        scope = Objects.requireNonNullElse(scope, "");
        message = Objects.requireNonNullElse(message, "");
    }
}
