package io.github.delokoseni.service.ntfs;

public final class NtfsFileRecordSegmentSize {
    private NtfsFileRecordSegmentSize() {
    }

    public static int decode(int signedEncoding, long clusterSizeBytes) {
        if (clusterSizeBytes <= 0) {
            throw new IllegalArgumentException("Размер кластера NTFS должен быть положительным.");
        }
        long recordSize;
        if (signedEncoding > 0) {
            try {
                recordSize = Math.multiplyExact((long) signedEncoding, clusterSizeBytes);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("Переполнение размера записи файла, заданного в кластерах.", e);
            }
        } else if (signedEncoding < 0) {
            int exponent = -signedEncoding;
            if (exponent >= Long.SIZE - 1) {
                throw new IllegalArgumentException("Показатель степени для размера записи меньше кластера не поддерживается.");
            }
            recordSize = 1L << exponent;
        } else {
            throw new IllegalArgumentException("Кодирование размера записи файла не может быть нулевым.");
        }
        if (recordSize <= 0 || recordSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Размер записи файла выходит за поддерживаемый положительный диапазон.");
        }
        return (int) recordSize;
    }

    public static void validateWindowsClusterCount(
            int recordSizeBytes, long clusterSizeBytes, int windowsClustersPerRecord) {
        if (recordSizeBytes <= 0 || clusterSizeBytes <= 0 || windowsClustersPerRecord < 0) {
            throw new IllegalArgumentException("Размеры записи файла NTFS и кластера должны быть допустимы.");
        }
        long expected;
        if (recordSizeBytes < clusterSizeBytes) {
            expected = 0;
        } else {
            if (recordSizeBytes % clusterSizeBytes != 0) {
                throw new IllegalArgumentException("Размер записи файла не кратен целому числу кластеров.");
            }
            expected = recordSizeBytes / clusterSizeBytes;
        }
        if (windowsClustersPerRecord != expected) {
            throw new IllegalArgumentException("Windows ClustersPerFileRecordSegment равно "
                    + windowsClustersPerRecord + ", ожидалось " + expected
                    + " согласно декодированному размеру записи файла NTFS.");
        }
    }

    public static String describeClustersPerRecord(long recordSizeBytes, long clusterSizeBytes) {
        if (recordSizeBytes <= 0 || clusterSizeBytes <= 0) {
            throw new IllegalArgumentException("Размеры записи файла NTFS и кластера должны быть положительными.");
        }
        if (recordSizeBytes == clusterSizeBytes) {
            return "1 кластер";
        }
        if (recordSizeBytes < clusterSizeBytes && clusterSizeBytes % recordSizeBytes == 0) {
            return "1/" + (clusterSizeBytes / recordSizeBytes) + " кластера";
        }
        if (recordSizeBytes > clusterSizeBytes && recordSizeBytes % clusterSizeBytes == 0) {
            return (recordSizeBytes / clusterSizeBytes) + " кластеров";
        }
        return recordSizeBytes + "/" + clusterSizeBytes + " кластера";
    }
}
