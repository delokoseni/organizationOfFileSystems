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
        if (lower.contains("crc32") && lower.contains("gpt")
                || lower.contains("записи разделов в основной и резервной копиях gpt различаются")
                || lower.contains("гибридная или неоднозначная разметка")
                || lower.contains("гибридная разметка")
                || lower.contains("разметка неоднозначна")
                || lower.contains("gpt") && lower.contains("остаточ")
                || lower.contains("остаточные или несогласованные gpt-метаданные")
                || lower.contains("противоречивые метаданные gpt")
                || lower.contains("защитная запись mbr")
                || lower.contains("перекрывает действительный mbr-раздел")
                || lower.contains("защитный mbr отсутствует")
                || lower.contains("область windows длиннее ntfs")
                || lower.contains("не совпадает на lba")) {
            return DiagnosticSeverity.WARNING;
        }
        if (lower.contains("crc32") && lower.contains("gpt")
                && (lower.contains("(резервная копия)") || lower.contains("(основная копия)")
                || lower.contains("(backup)") || lower.contains("(primary)"))) {
            return DiagnosticSeverity.WARNING;
        }
        if (lower.contains("размер физического сектора недоступен")
                || lower.contains("не удалось получить размер физического сектора")
                || lower.contains("физического сектора не удалось получить")) {
            return DiagnosticSeverity.INFORMATION;
        }
        if (lower.contains("анализ ntfs недоступен для файловой системы")
                || lower.contains("анализ ntfs не подтверждён")
                || lower.contains("подтверждение не получено")
                || lower.contains("проверка загрузочного сектора ntfs пропущена")) {
            return DiagnosticSeverity.INFORMATION;
        }
        if (lower.contains("ошиб") || lower.contains("недопустим") || lower.contains("недейств")
                || lower.contains("переполн") || lower.contains("превыш") || lower.contains("пуст")
                || lower.contains("выходит за") || lower.contains("не удалось") || lower.contains("изменил")
                || lower.contains("не сопостав") || lower.contains("перекрыва")
                || lower.contains("нет копии gpt") || lower.contains("несогласован")) {
            return DiagnosticSeverity.ERROR;
        }
        if (lower.contains("не поддерживается") || lower.contains("недоступ")
                || lower.contains("не обнаружен") || lower.contains("не найден")
                || lower.contains("отличается") || lower.contains("разницу")
                || lower.contains("различаются") || lower.contains("меньше размера")
                || lower.contains("отсутствует") || lower.contains("не читается")) {
            return DiagnosticSeverity.WARNING;
        }
        if (lower.contains("не подтвержд") || lower.contains("не выполнялся")
                || lower.contains("пропущен")) {
            return DiagnosticSeverity.INFORMATION;
        }
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
