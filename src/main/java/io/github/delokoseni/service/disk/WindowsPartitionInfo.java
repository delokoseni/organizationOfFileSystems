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
            throw new IllegalArgumentException("Номер раздела Windows, смещение и длина не могут быть отрицательными.");
        }
        typeGuid = typeGuid == null ? "" : typeGuid;
        uniqueGuid = uniqueGuid == null ? "" : uniqueGuid;
    }
}
