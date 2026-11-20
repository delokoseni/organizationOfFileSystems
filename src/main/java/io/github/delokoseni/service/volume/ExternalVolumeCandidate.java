package io.github.delokoseni.service.volume;

import io.github.delokoseni.model.VolumeInspection;
import io.github.delokoseni.service.disk.WindowsPartitionInfo;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

public final class ExternalVolumeCandidate {
    private final VolumeInspection volumeInspection;
    private final int physicalDiskNumber;
    private final String model;
    private final String serialNumber;
    private final long diskCapacityBytes;
    private final int logicalSectorSize;
    private final OptionalInt physicalSectorSize;
    private final List<WindowsPartitionInfo> partitions;

    ExternalVolumeCandidate(
            VolumeInspection volumeInspection,
            int physicalDiskNumber,
            String model,
            String serialNumber,
            long diskCapacityBytes,
            int logicalSectorSize,
            OptionalInt physicalSectorSize,
            List<WindowsPartitionInfo> partitions) {
        this.volumeInspection = Objects.requireNonNull(volumeInspection);
        this.physicalDiskNumber = physicalDiskNumber;
        this.model = Objects.requireNonNull(model);
        this.serialNumber = Objects.requireNonNullElse(serialNumber, "");
        this.diskCapacityBytes = diskCapacityBytes;
        this.logicalSectorSize = logicalSectorSize;
        this.physicalSectorSize = Objects.requireNonNull(physicalSectorSize);
        this.partitions = List.copyOf(partitions);
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
}
