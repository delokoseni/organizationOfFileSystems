package io.github.delokoseni.service.volume;

import io.github.delokoseni.model.DiskExtent;
import io.github.delokoseni.model.VolumeInspection;

import java.util.List;

public final class VolumeSelectionService {
    public VolumeInspection resolveCurrentSelection(
            List<VolumeInspection> currentVolumes, String selectedVolumeGuidPath) {
        if (selectedVolumeGuidPath == null || selectedVolumeGuidPath.isBlank()) {
            throw new IllegalArgumentException("Путь GUID выбранного тома не задан.");
        }
        List<VolumeInspection> matches = currentVolumes.stream()
                .filter(volume -> volume.volume().volumeGuidPath().equalsIgnoreCase(selectedVolumeGuidPath))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException(matches.isEmpty()
                    ? "Выбранный том больше не обнаружен; другой том не выбран."
                    : "Идентификатор выбранного тома неоднозначен.");
        }
        VolumeInspection selected = matches.getFirst();
        if (selected.diskExtents().isEmpty()) {
            throw new IllegalArgumentException("Физические дисковые области выбранного тома недоступны.");
        }
        long distinctDiskCount = selected.diskExtents().stream()
                .map(DiskExtent::diskNumber).distinct().count();
        if (distinctDiskCount != 1) {
            throw new IllegalArgumentException("Том охватывает несколько физических дисков; "
                    + "этот тип конфигурации не поддерживается для текущего анализа.");
        }
        return selected;
    }
}
