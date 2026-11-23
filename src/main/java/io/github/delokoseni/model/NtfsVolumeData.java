package io.github.delokoseni.model;

public record NtfsVolumeData(
        long volumeSerialNumber,
        long numberOfSectors,
        long totalClusters,
        long freeClusters,
        long reservedClusters,
        int bytesPerSector,
        int bytesPerCluster,
        int bytesPerFileRecordSegment,
        int clustersPerFileRecordSegment,
        long mftValidDataLength,
        long mftStartLcn,
        long mftMirrorStartLcn,
        long mftZoneStartLcn,
        long mftZoneEndLcn) {

    public NtfsVolumeData {
        if (numberOfSectors < 0 || totalClusters < 0 || freeClusters < 0 || reservedClusters < 0
                || bytesPerSector < 0 || bytesPerCluster < 0 || bytesPerFileRecordSegment < 0
                || mftValidDataLength < 0) {
            throw new IllegalArgumentException("Счётчики и размеры NTFS не могут быть отрицательными.");
        }
    }
}
