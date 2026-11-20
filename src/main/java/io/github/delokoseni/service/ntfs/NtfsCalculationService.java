package io.github.delokoseni.service.ntfs;

import io.github.delokoseni.model.NtfsMetrics;
import io.github.delokoseni.model.NtfsVolumeData;

public final class NtfsCalculationService {
    public NtfsMetrics calculate(NtfsVolumeData data) {
        if (data.bytesPerSector() <= 0 || data.bytesPerCluster() <= 0
                || data.numberOfSectors() <= 0 || data.totalClusters() <= 0) {
            throw new IllegalArgumentException("NTFS sector geometry and volume counts must be positive");
        }
        if (data.bytesPerCluster() % data.bytesPerSector() != 0) {
            throw new IllegalArgumentException("Cluster size must be a whole number of sectors");
        }
        if (data.freeClusters() > data.totalClusters()
                || data.reservedClusters() > data.totalClusters()
                || data.freeClusters() > data.totalClusters() - data.reservedClusters()) {
            throw new IllegalArgumentException("NTFS free and reserved cluster counts are inconsistent");
        }
        validateLcn(data.mftStartLcn(), data.totalClusters(), false, "MFT");
        validateLcn(data.mftMirrorStartLcn(), data.totalClusters(), false, "MFT mirror");
        validateLcn(data.mftZoneStartLcn(), data.totalClusters(), true, "MFT zone start");
        validateLcn(data.mftZoneEndLcn(), data.totalClusters(), true, "MFT zone end");
        if (data.mftZoneEndLcn() < data.mftZoneStartLcn()) {
            throw new IllegalArgumentException("NTFS MFT zone end precedes its start");
        }
        if (data.bytesPerFileRecordSegment() <= 0) {
            throw new IllegalArgumentException("NTFS file record segment size must be positive");
        }

        try {
            long sectorsPerCluster = data.bytesPerCluster() / data.bytesPerSector();
            long totalCapacity = Math.multiplyExact(data.totalClusters(), (long) data.bytesPerCluster());
            long freeCapacity = Math.multiplyExact(data.freeClusters(), (long) data.bytesPerCluster());
            long usedClusters = Math.subtractExact(data.totalClusters(), data.freeClusters());
            long totalSectorCapacity = Math.multiplyExact(data.numberOfSectors(), (long) data.bytesPerSector());
            if (totalCapacity > totalSectorCapacity
                    || data.totalClusters() > data.numberOfSectors() / sectorsPerCluster) {
                throw new IllegalArgumentException("NTFS cluster count exceeds reported volume sectors");
            }
            if (data.mftValidDataLength() > totalCapacity) {
                throw new IllegalArgumentException("NTFS MFT valid data length exceeds cluster capacity");
            }
            long mftOffset = Math.multiplyExact(data.mftStartLcn(), (long) data.bytesPerCluster());
            long mirrorOffset = Math.multiplyExact(data.mftMirrorStartLcn(), (long) data.bytesPerCluster());
            long zoneClusters = Math.subtractExact(data.mftZoneEndLcn(), data.mftZoneStartLcn());
            long zoneBytes = Math.multiplyExact(zoneClusters, (long) data.bytesPerCluster());
            return new NtfsMetrics(sectorsPerCluster, totalCapacity, usedClusters, freeCapacity,
                    mftOffset, mirrorOffset, data.bytesPerFileRecordSegment(), zoneBytes);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("NTFS metric calculation overflows 64-bit capacity", e);
        }
    }

    private static void validateLcn(long lcn, long totalClusters, boolean allowEnd, String field) {
        if (lcn < 0 || lcn > totalClusters || (!allowEnd && lcn == totalClusters)) {
            throw new IllegalArgumentException(field + " LCN is outside the volume");
        }
    }
}
