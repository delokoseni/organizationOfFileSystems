package io.github.delokoseni.service.volume;

import io.github.delokoseni.model.DiskExtent;
import io.github.delokoseni.model.VolumeInspection;

import java.util.List;
import java.util.UUID;

public final class NtfsAnalysisReadiness {
    private NtfsAnalysisReadiness() {
    }

    public static Readiness evaluate(VolumeInspection selected, List<VolumeInspection> currentVolumes) {
        if (selected == null) {
            return unavailable("Выберите том для анализа.");
        }
        String path = selected.volume().volumeGuidPath();
        if (path == null || !isVolumeGuidPath(path)) {
            return unavailable("У выбранного тома отсутствует допустимый путь GUID.");
        }
        long matchingVolumes = currentVolumes.stream()
                .filter(volume -> volume.volume().volumeGuidPath().equalsIgnoreCase(path))
                .count();
        if (matchingVolumes != 1) {
            return unavailable(matchingVolumes == 0
                    ? "Выбранный том больше не обнаружен; другой том не выбран."
                    : "Идентификатор выбранного тома неоднозначен.");
        }
        String fileSystem = selected.volume().fileSystem();
        if (FileSystemType.fromWindowsName(fileSystem) != FileSystemType.NTFS) {
            return unavailable(fileSystem == null || fileSystem.isBlank()
                    ? "Файловая система выбранного тома не определена."
                    : "Анализ NTFS недоступен: выбранный том использует файловую систему "
                    + fileSystem + ".");
        }
        List<DiskExtent> extents = selected.diskExtents();
        if (extents.isEmpty()) {
            return unavailable("Физические области выбранного тома недоступны.");
        }
        if (extents.stream().map(DiskExtent::diskNumber).distinct().count() != 1) {
            return unavailable("Том охватывает несколько физических дисков; этот тип анализа не поддерживается.");
        }
        for (DiskExtent extent : extents) {
            if (extent.extentLengthBytes() <= 0) {
                return unavailable("Область выбранного тома пуста; анализ NTFS не запущен.");
            }
            try {
                Math.addExact(extent.startingOffsetBytes(), extent.extentLengthBytes());
            } catch (ArithmeticException e) {
                return unavailable("Границы области выбранного тома переполняются.");
            }
        }
        return new Readiness(true, "Том NTFS обнаружен. Доступ на чтение и актуальность будут повторно проверены "
                + "перед запросом Windows.");
    }

    private static boolean isVolumeGuidPath(String path) {
        String prefix = "\\\\?\\Volume{";
        if (!path.regionMatches(true, 0, prefix, 0, prefix.length()) || !path.endsWith("}\\")) {
            return false;
        }
        String value = path.substring(prefix.length(), path.length() - 2);
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Readiness unavailable(String reason) {
        return new Readiness(false, reason);
    }

    public record Readiness(boolean available, String explanation) {
    }
}
