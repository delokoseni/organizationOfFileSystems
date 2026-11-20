package io.github.delokoseni.model;

import java.util.Objects;

public record DiskInfo(
        int diskNumber,
        String devicePath,
        String model,
        String vendor,
        String serialNumber,
        long capacityBytes,
        int logicalSectorSize,
        int physicalSectorSize,
        boolean removable,
        PartitionStyle partitionStyle) {

    public DiskInfo {
        if (diskNumber < 0 || capacityBytes < 0
                || logicalSectorSize < 0 || physicalSectorSize < 0) {
            throw new IllegalArgumentException("Disk numbers, sizes, and capacities cannot be negative");
        }
        devicePath = Objects.requireNonNullElse(devicePath, "");
        model = Objects.requireNonNullElse(model, "");
        vendor = Objects.requireNonNullElse(vendor, "");
        serialNumber = Objects.requireNonNullElse(serialNumber, "");
        partitionStyle = Objects.requireNonNullElse(partitionStyle, PartitionStyle.UNKNOWN);
    }
}
