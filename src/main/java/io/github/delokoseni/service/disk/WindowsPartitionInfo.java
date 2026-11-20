package io.github.delokoseni.service.disk;

public record WindowsPartitionInfo(
        int partitionNumber,
        long startingOffsetBytes,
        long lengthBytes,
        int mbrType,
        String typeGuid,
        String uniqueGuid) {
    public WindowsPartitionInfo {
        if (partitionNumber < 0 || startingOffsetBytes < 0 || lengthBytes < 0) {
            throw new IllegalArgumentException("Windows partition number, offset, and length cannot be negative");
        }
        typeGuid = typeGuid == null ? "" : typeGuid;
        uniqueGuid = uniqueGuid == null ? "" : uniqueGuid;
    }
}
