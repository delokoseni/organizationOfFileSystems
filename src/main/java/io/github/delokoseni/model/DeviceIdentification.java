package io.github.delokoseni.model;

import java.util.Optional;

public record DeviceIdentification(Status status, Optional<DiskInfo> disk, String message) {
    public enum Status {
        IDENTIFIED,
        TARGET_VOLUME_NOT_FOUND,
        AMBIGUOUS_TARGET_VOLUME,
        DISK_MAPPING_UNAVAILABLE,
        AMBIGUOUS_DISK_MAPPING,
        MODEL_UNAVAILABLE,
        MODEL_MISMATCH
    }

    public DeviceIdentification {
        disk = disk == null ? Optional.empty() : disk;
        message = message == null ? "" : message;
        if ((status == Status.IDENTIFIED) != disk.isPresent()) {
            throw new IllegalArgumentException("Only a successful identification may contain a disk");
        }
    }
}
