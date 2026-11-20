package io.github.delokoseni.service.disk;

import io.github.delokoseni.model.DeviceIdentification;
import io.github.delokoseni.model.DiskInfo;
import io.github.delokoseni.model.VolumeInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ExternalDeviceIdentificationService {
    private static final String TARGET_MOUNT_POINT = "D:";
    private static final String EXPECTED_MODEL = "ADATA SE760";

    public DeviceIdentification identifyExpectedDevice(
            List<DiskInfo> disks,
            List<VolumeInfo> volumes,
            Map<String, Set<Integer>> diskNumbersByVolumeGuid) {
        List<VolumeInfo> targetVolumes = volumes.stream()
                .filter(volume -> volume.mountPoints().stream().anyMatch(this::isTargetMountPoint))
                .toList();

        if (targetVolumes.isEmpty()) {
            return failure(DeviceIdentification.Status.TARGET_VOLUME_NOT_FOUND,
                    "Volume D: was not found; no physical disk was selected.");
        }
        if (targetVolumes.size() != 1) {
            return failure(DeviceIdentification.Status.AMBIGUOUS_TARGET_VOLUME,
                    "More than one volume is associated with D:; identification is disabled.");
        }

        Set<Integer> diskNumbers = diskNumbersByVolumeGuid.get(targetVolumes.getFirst().volumeGuidPath());
        if (diskNumbers == null || diskNumbers.isEmpty()) {
            return failure(DeviceIdentification.Status.DISK_MAPPING_UNAVAILABLE,
                    "D: could not be mapped to a physical disk; identification is disabled.");
        }
        if (diskNumbers.size() != 1) {
            return failure(DeviceIdentification.Status.AMBIGUOUS_DISK_MAPPING,
                    "D: spans multiple physical disks; identification is disabled.");
        }

        int mappedDiskNumber = diskNumbers.iterator().next();
        List<DiskInfo> mappedDisks = disks.stream()
                .filter(disk -> disk.diskNumber() == mappedDiskNumber)
                .toList();
        if (mappedDisks.size() != 1) {
            return failure(DeviceIdentification.Status.AMBIGUOUS_DISK_MAPPING,
                    "The mapped physical disk is missing or ambiguous; identification is disabled.");
        }

        DiskInfo disk = mappedDisks.getFirst();
        if (disk.model().isBlank()) {
            return failure(DeviceIdentification.Status.MODEL_UNAVAILABLE,
                    "The mapped disk model is unavailable; the external-device test is disabled.");
        }
        if (!EXPECTED_MODEL.equalsIgnoreCase(normalizeModel(disk.model()))) {
            return failure(DeviceIdentification.Status.MODEL_MISMATCH,
                    "D: maps to a disk whose model is not exactly ADATA SE760; the external-device test is disabled.");
        }
        return new DeviceIdentification(DeviceIdentification.Status.IDENTIFIED,
                java.util.Optional.of(disk), "ADATA SE760 identified as physical disk " + disk.diskNumber()
                + ". Explicit user selection is still required before any analysis.");
    }

    private static String normalizeModel(String model) {
        String normalized = model.trim();
        String windowsDeviceSuffix = " SCSI Disk Device";
        if (normalized.regionMatches(true,
                Math.max(0, normalized.length() - windowsDeviceSuffix.length()),
                windowsDeviceSuffix, 0, windowsDeviceSuffix.length())) {
            normalized = normalized.substring(0, normalized.length() - windowsDeviceSuffix.length()).trim();
        }
        return normalized;
    }

    private boolean isTargetMountPoint(String mountPoint) {
        return mountPoint != null && (TARGET_MOUNT_POINT.equalsIgnoreCase(mountPoint.trim())
                || "D:\\".equalsIgnoreCase(mountPoint.trim()));
    }

    private static DeviceIdentification failure(DeviceIdentification.Status status, String message) {
        return new DeviceIdentification(status, java.util.Optional.empty(), message);
    }
}
