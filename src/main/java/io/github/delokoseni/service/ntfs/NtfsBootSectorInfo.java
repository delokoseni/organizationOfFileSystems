package io.github.delokoseni.service.ntfs;

public record NtfsBootSectorInfo(
        int bytesPerSector,
        int sectorsPerCluster,
        long totalSectors,
        long mftStartLcn,
        long mftMirrorStartLcn,
        int fileRecordSizeEncoding,
        int fileRecordSizeBytes) {
}
