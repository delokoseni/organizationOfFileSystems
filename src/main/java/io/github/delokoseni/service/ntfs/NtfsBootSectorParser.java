package io.github.delokoseni.service.ntfs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class NtfsBootSectorParser {
    private static final byte[] OEM_ID = "NTFS    ".getBytes(StandardCharsets.US_ASCII);
    private static final int BOOT_SECTOR_SIZE = 512;

    public NtfsBootSectorInfo parse(byte[] sector) throws IOException {
        if (sector == null || sector.length < BOOT_SECTOR_SIZE) {
            throw new IOException("Загрузочный сектор NTFS усечён.");
        }
        if (!Arrays.equals(OEM_ID, Arrays.copyOfRange(sector, 3, 11))) {
            throw new IOException("В загрузочном секторе NTFS отсутствует OEM-идентификатор.");
        }
        if ((sector[510] & 0xff) != 0x55 || (sector[511] & 0xff) != 0xaa) {
            throw new IOException("Сигнатура загрузочного сектора NTFS недействительна.");
        }

        int bytesPerSector = u16(sector, 11);
        int sectorsPerCluster = u8(sector, 13);
        long totalSectors = u64(sector, 40, "TotalSectors");
        long mftStartLcn = u64(sector, 48, "MftStartLcn");
        long mftMirrorStartLcn = u64(sector, 56, "Mft2StartLcn");
        int recordEncoding = (byte) sector[64];
        if (!isPowerOfTwo(bytesPerSector) || bytesPerSector < 512
                || !isPowerOfTwo(sectorsPerCluster) || totalSectors <= 0) {
            throw new IOException("Геометрия загрузочного сектора NTFS или TotalSectors недопустима.");
        }
        long clusterBytes;
        try {
            clusterBytes = Math.multiplyExact((long) bytesPerSector, sectorsPerCluster);
        } catch (ArithmeticException e) {
            throw new IOException("Переполнение размера кластера загрузочного сектора NTFS.", e);
        }
        int recordSizeBytes;
        try {
            recordSizeBytes = NtfsFileRecordSegmentSize.decode(recordEncoding, clusterBytes);
        } catch (IllegalArgumentException e) {
            throw new IOException("Кодирование размера записи файла в загрузочном секторе NTFS недопустимо: "
                    + e.getMessage(), e);
        }
        return new NtfsBootSectorInfo(bytesPerSector, sectorsPerCluster, totalSectors,
                mftStartLcn, mftMirrorStartLcn, recordEncoding, recordSizeBytes);
    }

    private static int u8(byte[] bytes, int offset) {
        return bytes[offset] & 0xff;
    }

    private static int u16(byte[] bytes, int offset) {
        return u8(bytes, offset) | u8(bytes, offset + 1) << 8;
    }

    private static long u64(byte[] bytes, int offset, String field) throws IOException {
        long value = ((long) i32(bytes, offset + 4) << 32)
                | Integer.toUnsignedLong(i32(bytes, offset));
        if (value < 0) {
            throw new IOException("Поле " + field + " загрузочного сектора NTFS выходит за поддерживаемый "
                    + "знаковый 64-разрядный диапазон.");
        }
        return value;
    }

    private static int i32(byte[] bytes, int offset) {
        return u8(bytes, offset) | u8(bytes, offset + 1) << 8
                | u8(bytes, offset + 2) << 16 | u8(bytes, offset + 3) << 24;
    }

    private static boolean isPowerOfTwo(int value) {
        return value > 0 && (value & (value - 1)) == 0;
    }
}
