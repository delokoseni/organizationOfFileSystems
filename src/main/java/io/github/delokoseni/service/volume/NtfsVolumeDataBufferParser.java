package io.github.delokoseni.service.volume;

import io.github.delokoseni.model.NtfsVolumeData;

import java.io.IOException;
import java.util.Objects;

final class NtfsVolumeDataBufferParser {
    static final int STRUCTURE_SIZE = 96;

    private NtfsVolumeDataBufferParser() {
    }

    static NtfsVolumeData parse(byte[] buffer, int bytesReturned) throws IOException {
        Objects.requireNonNull(buffer, "buffer");
        if (bytesReturned < STRUCTURE_SIZE) {
            throw new IOException("Структура NTFS_VOLUME_DATA_BUFFER усечена: bytesReturned="
                    + bytesReturned + ", требуется=" + STRUCTURE_SIZE);
        }
        if (bytesReturned > buffer.length) {
            throw new IOException("NTFS_VOLUME_DATA_BUFFER bytesReturned=" + bytesReturned
                    + " превышает размер выходного буфера=" + buffer.length);
        }

        try {
            return new NtfsVolumeData(
                    readInt64Bits(buffer, 0),
                    readNonNegativeInt64(buffer, 8, "NumberOfSectors"),
                    readNonNegativeInt64(buffer, 16, "TotalClusters"),
                    readNonNegativeInt64(buffer, 24, "FreeClusters"),
                    readNonNegativeInt64(buffer, 32, "TotalReserved"),
                    readPositiveInt32(buffer, 40, "BytesPerSector"),
                    readPositiveInt32(buffer, 44, "BytesPerCluster"),
                    readPositiveInt32(buffer, 48, "BytesPerFileRecordSegment"),
                    readNonNegativeInt32(buffer, 52, "ClustersPerFileRecordSegment"),
                    readNonNegativeInt64(buffer, 56, "MftValidDataLength"),
                    readNonNegativeInt64(buffer, 64, "MftStartLcn"),
                    readNonNegativeInt64(buffer, 72, "Mft2StartLcn"),
                    readNonNegativeInt64(buffer, 80, "MftZoneStart"),
                    readNonNegativeInt64(buffer, 88, "MftZoneEnd"));
        } catch (IllegalArgumentException e) {
            throw new IOException("Ошибка проверки NTFS_VOLUME_DATA_BUFFER: " + e.getMessage(), e);
        }
    }

    private static long readInt64Bits(byte[] bytes, int offset) {
        return ((long) readInt32(bytes, offset + 4) << 32)
                | Integer.toUnsignedLong(readInt32(bytes, offset));
    }

    private static long readNonNegativeInt64(byte[] bytes, int offset, String field) throws IOException {
        long value = readInt64Bits(bytes, offset);
        if (value < 0) {
            throw new IOException("NTFS_VOLUME_DATA_BUFFER." + field
                    + " отрицательно или выходит за поддерживаемый знаковый 64-разрядный диапазон: " + value);
        }
        return value;
    }

    private static int readPositiveInt32(byte[] bytes, int offset, String field) throws IOException {
        long value = Integer.toUnsignedLong(readInt32(bytes, offset));
        if (value == 0 || value > Integer.MAX_VALUE) {
            throw new IOException("NTFS_VOLUME_DATA_BUFFER." + field
                    + " должно быть положительным поддерживаемым DWORD, получено " + value);
        }
        return (int) value;
    }

    private static int readNonNegativeInt32(byte[] bytes, int offset, String field) throws IOException {
        long value = Integer.toUnsignedLong(readInt32(bytes, offset));
        if (value > Integer.MAX_VALUE) {
            throw new IOException("NTFS_VOLUME_DATA_BUFFER." + field
                    + " превышает поддерживаемый диапазон DWORD: " + value);
        }
        return (int) value;
    }

    private static int readInt32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }
}
