package io.github.delokoseni.service.parser;

import io.github.delokoseni.model.PartitionStyle;
import io.github.delokoseni.service.disk.WindowsPartitionInfo;
import io.github.delokoseni.service.disk.WindowsPartitionLayout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PartitionComparisonService {
    public List<String> compare(
            PartitionStyle parsedStyle,
            List<PartitionRecord> parsedPartitions,
            WindowsPartitionLayout windowsLayout) {
        List<String> differences = new ArrayList<>();
        if (parsedStyle != PartitionStyle.MBR && parsedStyle != PartitionStyle.GPT) {
            return List.of("Сопоставление разделов с Windows пропущено: независимая таблица "
                    + "не определена однозначно (" + parsedStyle + ").");
        }
        if (parsedStyle != windowsLayout.style()) {
            differences.add("Тип таблицы разделов отличается: анализатор=" + parsedStyle
                    + ", Windows=" + windowsLayout.style() + ".");
        }

        List<PartitionRecord> independent = parsedPartitions.stream()
                .sorted(Comparator.comparingLong(PartitionRecord::startingOffsetBytes)).toList();
        List<WindowsPartitionInfo> windows = windowsLayout.partitions().stream()
                .sorted(Comparator.comparingLong(WindowsPartitionInfo::startingOffsetBytes)).toList();
        if (independent.size() != windows.size()) {
            differences.add("Количество разделов отличается: анализатор=" + independent.size()
                    + ", Windows=" + windows.size() + ".");
        }
        int sharedCount = Math.min(independent.size(), windows.size());
        for (int i = 0; i < sharedCount; i++) {
            PartitionRecord parsed = independent.get(i);
            WindowsPartitionInfo win = windows.get(i);
            if (win.partitionNumber() != i + 1) {
                differences.add("Номер раздела Windows в отсортированной позиции " + (i + 1)
                        + " равен " + win.partitionNumber() + ".");
            }
            if (parsed.startingOffsetBytes() != win.startingOffsetBytes()) {
                differences.add("Начальное смещение раздела " + (i + 1) + " отличается: анализатор="
                        + parsed.startingOffsetBytes() + ", Windows=" + win.startingOffsetBytes() + ".");
            }
            if (parsed.lengthBytes() != win.lengthBytes()) {
                differences.add("Длина раздела " + (i + 1) + " отличается: анализатор="
                        + parsed.lengthBytes() + ", Windows=" + win.lengthBytes() + ".");
            }
            if (parsedStyle == PartitionStyle.MBR && parsed.mbrType() != win.mbrType()) {
                differences.add("Тип MBR раздела " + (i + 1) + " отличается.");
            }
            if (parsedStyle == PartitionStyle.GPT
                    && (!parsed.typeGuid().equalsIgnoreCase(win.typeGuid())
                    || !parsed.uniqueGuid().equalsIgnoreCase(win.uniqueGuid()))) {
                differences.add("Идентификаторы GPT раздела " + (i + 1) + " отличаются.");
            }
        }
        return List.copyOf(differences);
    }
}
