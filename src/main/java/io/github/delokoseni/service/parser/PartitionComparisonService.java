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
        if (parsedStyle != windowsLayout.style()) {
            differences.add("Partition style differs: parser=" + parsedStyle
                    + ", Windows=" + windowsLayout.style() + ".");
        }

        List<PartitionRecord> independent = parsedPartitions.stream()
                .sorted(Comparator.comparingLong(PartitionRecord::startingOffsetBytes)).toList();
        List<WindowsPartitionInfo> windows = windowsLayout.partitions().stream()
                .sorted(Comparator.comparingLong(WindowsPartitionInfo::startingOffsetBytes)).toList();
        if (independent.size() != windows.size()) {
            differences.add("Partition count differs: parser=" + independent.size()
                    + ", Windows=" + windows.size() + ".");
        }
        int sharedCount = Math.min(independent.size(), windows.size());
        for (int i = 0; i < sharedCount; i++) {
            PartitionRecord parsed = independent.get(i);
            WindowsPartitionInfo win = windows.get(i);
            if (win.partitionNumber() != i + 1) {
                differences.add("Windows partition number at sorted position " + (i + 1)
                        + " is " + win.partitionNumber() + ".");
            }
            if (parsed.startingOffsetBytes() != win.startingOffsetBytes()) {
                differences.add("Partition " + (i + 1) + " starting offset differs: parser="
                        + parsed.startingOffsetBytes() + ", Windows=" + win.startingOffsetBytes() + ".");
            }
            if (parsed.lengthBytes() != win.lengthBytes()) {
                differences.add("Partition " + (i + 1) + " length differs: parser="
                        + parsed.lengthBytes() + ", Windows=" + win.lengthBytes() + ".");
            }
            if (parsedStyle == PartitionStyle.MBR && parsed.mbrType() != win.mbrType()) {
                differences.add("Partition " + (i + 1) + " MBR type differs.");
            }
            if (parsedStyle == PartitionStyle.GPT
                    && (!parsed.typeGuid().equalsIgnoreCase(win.typeGuid())
                    || !parsed.uniqueGuid().equalsIgnoreCase(win.uniqueGuid()))) {
                differences.add("Partition " + (i + 1) + " GPT identifiers differ.");
            }
        }
        return List.copyOf(differences);
    }
}
