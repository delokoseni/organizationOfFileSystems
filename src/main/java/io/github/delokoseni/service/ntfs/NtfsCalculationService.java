package io.github.delokoseni.service.ntfs;

import io.github.delokoseni.model.NtfsMetrics;
import io.github.delokoseni.model.NtfsVolumeData;

public final class NtfsCalculationService {
    public NtfsMetrics calculate(NtfsVolumeData data) {
        if (data.bytesPerSector() <= 0 || data.bytesPerCluster() <= 0
                || data.numberOfSectors() <= 0 || data.totalClusters() <= 0) {
            throw new IllegalArgumentException("Размеры секторов NTFS и счётчики тома должны быть положительными.");
        }
        if (data.bytesPerCluster() % data.bytesPerSector() != 0) {
            throw new IllegalArgumentException("Размер кластера должен содержать целое число секторов.");
        }
        if (data.freeClusters() > data.totalClusters()
                || data.reservedClusters() > data.totalClusters()
                || data.freeClusters() > data.totalClusters() - data.reservedClusters()) {
            throw new IllegalArgumentException("Количество свободных и зарезервированных кластеров NTFS несогласовано.");
        }
        validateLcn(data.mftStartLcn(), data.totalClusters(), false, "MFT");
        validateLcn(data.mftMirrorStartLcn(), data.totalClusters(), false, "MFT mirror");
        validateLcn(data.mftZoneStartLcn(), data.totalClusters(), true, "MFT zone start");
        validateLcn(data.mftZoneEndLcn(), data.totalClusters(), true, "MFT zone end");
        if (data.mftZoneEndLcn() < data.mftZoneStartLcn()) {
            throw new IllegalArgumentException("Конец зоны MFT NTFS предшествует её началу.");
        }
        if (data.bytesPerFileRecordSegment() <= 0) {
            throw new IllegalArgumentException("Размер сегмента записи файла NTFS должен быть положительным.");
        }

        try {
            long sectorsPerCluster = data.bytesPerCluster() / data.bytesPerSector();
            long totalCapacity = Math.multiplyExact(data.totalClusters(), (long) data.bytesPerCluster());
            long freeCapacity = Math.multiplyExact(data.freeClusters(), (long) data.bytesPerCluster());
            long usedClusters = Math.subtractExact(data.totalClusters(), data.freeClusters());
            long totalSectorCapacity = Math.multiplyExact(data.numberOfSectors(), (long) data.bytesPerSector());
            if (totalCapacity > totalSectorCapacity
                    || data.totalClusters() > data.numberOfSectors() / sectorsPerCluster) {
                throw new IllegalArgumentException("Количество кластеров NTFS превышает число секторов тома.");
            }
            if (data.mftValidDataLength() > totalCapacity) {
                throw new IllegalArgumentException("Длина действительных данных MFT превышает ёмкость кластеров.");
            }
            long mftOffset = Math.multiplyExact(data.mftStartLcn(), (long) data.bytesPerCluster());
            long mirrorOffset = Math.multiplyExact(data.mftMirrorStartLcn(), (long) data.bytesPerCluster());
            long zoneClusters = Math.subtractExact(data.mftZoneEndLcn(), data.mftZoneStartLcn());
            long zoneBytes = Math.multiplyExact(zoneClusters, (long) data.bytesPerCluster());
            return new NtfsMetrics(sectorsPerCluster, totalCapacity, usedClusters, freeCapacity,
                    mftOffset, mirrorOffset, data.bytesPerFileRecordSegment(), zoneBytes);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Переполнение 64-разрядного диапазона при расчёте параметров NTFS.", e);
        }
    }

    private static void validateLcn(long lcn, long totalClusters, boolean allowEnd, String field) {
        if (lcn < 0 || lcn > totalClusters || (!allowEnd && lcn == totalClusters)) {
            throw new IllegalArgumentException(field + " LCN находится за границами тома.");
        }
    }
}
