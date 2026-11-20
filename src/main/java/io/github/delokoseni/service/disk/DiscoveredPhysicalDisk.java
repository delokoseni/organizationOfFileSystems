package io.github.delokoseni.service.disk;

import java.util.Objects;

public record DiscoveredPhysicalDisk(int diskNumber, String devicePath, String model) {
    public DiscoveredPhysicalDisk {
        if (diskNumber < 0) {
            throw new IllegalArgumentException("Physical disk number cannot be negative");
        }
        devicePath = Objects.requireNonNullElse(devicePath, "");
        model = Objects.requireNonNullElse(model, "");
    }
}
