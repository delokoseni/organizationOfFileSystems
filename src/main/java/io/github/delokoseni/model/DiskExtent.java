package io.github.delokoseni.model;

public record DiskExtent(int diskNumber, long startingOffsetBytes, long extentLengthBytes) {
    public DiskExtent {
        if (diskNumber < 0 || startingOffsetBytes < 0 || extentLengthBytes < 0) {
            throw new IllegalArgumentException("Номер диска, смещение и длина области не могут быть отрицательными.");
        }
    }
}
