package io.github.delokoseni.service.volume;

import io.github.delokoseni.model.VolumeInspection;
import io.github.delokoseni.service.disk.WindowsPartitionInfo;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

public final class VolumeAnalysisCandidate {
    private final VolumeInspection volumeInspection;
    private final int physicalDiskNumber;
    private final String model;
    private final String serialNumber;
    private final long diskCapacityBytes;
    private final int logicalSectorSize;
    private final OptionalInt physicalSectorSize;
    private final List<WindowsPartitionInfo> partitions;
    private final List<String> diagnostics;

    VolumeAnalysisCandidate(
            VolumeInspection volumeInspection,
            int physicalDiskNumber,
            String model,
            String serialNumber,
            long diskCapacityBytes,
            int logicalSectorSize,
            OptionalInt physicalSectorSize,
            List<WindowsPartitionInfo> partitions) {
        this(volumeInspection, physicalDiskNumber, model, serialNumber, diskCapacityBytes,
                logicalSectorSize, physicalSectorSize, partitions, List.of());
    }

    VolumeAnalysisCandidate(
            VolumeInspection volumeInspection,
            int physicalDiskNumber,
            String model,
            String serialNumber,
            long diskCapacityBytes,
            int logicalSectorSize,
            OptionalInt physicalSectorSize,
            List<WindowsPartitionInfo> partitions,
            List<String> diagnostics) {
        this.volumeInspection = Objects.requireNonNull(volumeInspection);
        this.physicalDiskNumber = physicalDiskNumber;
        this.model = Objects.requireNonNull(model);
        this.serialNumber = Objects.requireNonNullElse(serialNumber, "");
        this.diskCapacityBytes = diskCapacityBytes;
        this.logicalSectorSize = logicalSectorSize;
        this.physicalSectorSize = Objects.requireNonNull(physicalSectorSize);
        this.partitions = List.copyOf(partitions);
        this.diagnostics = List.copyOf(diagnostics);
    }

    public VolumeInspection volumeInspection() {
        return volumeInspection;
    }

    public int physicalDiskNumber() {
        return physicalDiskNumber;
    }

    public String model() {
        return model;
    }

    public String serialNumber() {
        return serialNumber;
    }

    public long diskCapacityBytes() {
        return diskCapacityBytes;
    }

    public int logicalSectorSize() {
        return logicalSectorSize;
    }

    public OptionalInt physicalSectorSize() {
        return physicalSectorSize;
    }

    public List<WindowsPartitionInfo> partitions() {
        return partitions;
    }

    public List<String> diagnostics() {
        return diagnostics;
    }
}
