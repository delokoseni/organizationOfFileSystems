package io.github.delokoseni.service.volume;

import io.github.delokoseni.model.DiskExtent;
import io.github.delokoseni.service.disk.WindowsPartitionInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class VolumePartitionMappingService {
    public List<ExtentPartitionMapping> map(
            List<DiskExtent> extents,
            Map<Integer, List<WindowsPartitionInfo>> partitionsByDiskNumber) {
        List<ExtentPartitionMapping> mappings = new ArrayList<>();
        for (DiskExtent extent : extents) {
            long extentEnd;
            try {
                extentEnd = Math.addExact(extent.startingOffsetBytes(), extent.extentLengthBytes());
            } catch (ArithmeticException e) {
                mappings.add(new ExtentPartitionMapping(extent, List.of(),
                        "Volume extent range overflows"));
                continue;
            }
            if (extent.extentLengthBytes() == 0) {
                mappings.add(new ExtentPartitionMapping(extent, List.of(), "Volume extent is empty"));
                continue;
            }

            List<WindowsPartitionInfo> candidates = partitionsByDiskNumber
                    .getOrDefault(extent.diskNumber(), List.of()).stream()
                    .filter(partition -> contains(partition, extent.startingOffsetBytes(), extentEnd))
                    .sorted(Comparator.comparingLong(WindowsPartitionInfo::startingOffsetBytes))
                    .toList();
            String diagnostic = candidates.size() == 1 ? ""
                    : candidates.isEmpty() ? "Extent does not fit wholly inside a known partition"
                    : "Extent maps ambiguously to multiple partitions";
            mappings.add(new ExtentPartitionMapping(extent, candidates, diagnostic));
        }
        return List.copyOf(mappings);
    }

    private static boolean contains(WindowsPartitionInfo partition, long extentStart, long extentEnd) {
        try {
            long partitionEnd = Math.addExact(partition.startingOffsetBytes(), partition.lengthBytes());
            return extentStart >= partition.startingOffsetBytes() && extentEnd <= partitionEnd;
        } catch (ArithmeticException e) {
            return false;
        }
    }

    public record ExtentPartitionMapping(
            DiskExtent extent,
            List<WindowsPartitionInfo> candidatePartitions,
            String diagnostic) {
        public ExtentPartitionMapping {
            candidatePartitions = List.copyOf(candidatePartitions);
        }

        public boolean isUnambiguous() {
            return candidatePartitions.size() == 1 && diagnostic.isEmpty();
        }
    }
}
