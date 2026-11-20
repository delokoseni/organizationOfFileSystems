package io.github.delokoseni.model;

public record NtfsMetrics(
        long sectorsPerCluster,
        long totalClusterCapacityBytes,
        long usedClusters,
        long freeClusterCapacityBytes,
        long mftStartOffsetWithinVolume,
        long mftMirrorStartOffsetWithinVolume,
        long mftRecordSizeBytes,
        long mftZoneSizeBytes) {
}
