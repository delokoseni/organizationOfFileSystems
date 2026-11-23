package io.github.delokoseni.service.ntfs;

import io.github.delokoseni.model.NtfsMetrics;
import io.github.delokoseni.model.NtfsVolumeData;
import io.github.delokoseni.model.VolumeFileSystemInfo;
import java.util.ArrayList;
import java.util.List;

public final class FilesystemConsistencyService {
    public List<String> validate(
            long partitionLengthBytes,
            long volumeExtentBytes,
            VolumeFileSystemInfo fileSystem,
            NtfsVolumeData ntfs,
            NtfsMetrics metrics) {
        return validateInternal(partitionLengthBytes, volumeExtentBytes, fileSystem, ntfs, metrics,
                null, false, false, null, null);
    }

    public List<String> validate(
            long partitionLengthBytes,
            long volumeExtentBytes,
            VolumeFileSystemInfo fileSystem,
            NtfsVolumeData ntfs,
            NtfsMetrics metrics,
            NtfsBootSectorInfo bootSector,
            boolean matchingBackupBootSector) {
        return validateInternal(partitionLengthBytes, volumeExtentBytes, fileSystem, ntfs, metrics,
                bootSector, matchingBackupBootSector, matchingBackupBootSector, null, null);
    }

    public List<String> validate(
            long partitionLengthBytes,
            long volumeExtentBytes,
            VolumeFileSystemInfo fileSystem,
            NtfsVolumeData ntfs,
            NtfsMetrics metrics,
            NtfsBootSectorInfo bootSector,
            boolean matchingTerminalBackupBootSector,
            boolean matchingBpbBoundaryBootSector,
            long extentStartingAbsoluteDiskLba) {
        return validateInternal(partitionLengthBytes, volumeExtentBytes, fileSystem, ntfs, metrics,
                bootSector, matchingTerminalBackupBootSector, matchingBpbBoundaryBootSector,
                extentStartingAbsoluteDiskLba, null);
    }

    public List<String> validate(
            long partitionLengthBytes,
            long volumeExtentBytes,
            VolumeFileSystemInfo fileSystem,
            NtfsVolumeData ntfs,
            NtfsMetrics metrics,
            NtfsBootSectorInfo bootSector,
            NtfsBackupBootSectorEvidence backupEvidence,
            long extentStartingAbsoluteDiskLba) {
        return validateInternal(partitionLengthBytes, volumeExtentBytes, fileSystem, ntfs, metrics,
                bootSector, backupEvidence != null && backupEvidence.matchingExtentTerminal(),
                backupEvidence != null && backupEvidence.bpbLastSectorReadable()
                        && backupEvidence.bpbLastSectorMatchesPrimary(),
                extentStartingAbsoluteDiskLba, backupEvidence);
    }

    private List<String> validateInternal(
            long partitionLengthBytes,
            long volumeExtentBytes,
            VolumeFileSystemInfo fileSystem,
            NtfsVolumeData ntfs,
            NtfsMetrics metrics,
            NtfsBootSectorInfo bootSector,
            boolean matchingTerminalBackupBootSector,
            boolean matchingBpbBoundaryBootSector,
            Long extentStartingAbsoluteDiskLba,
            NtfsBackupBootSectorEvidence backupEvidence) {
        if (partitionLengthBytes < 0 || volumeExtentBytes < 0) {
            throw new IllegalArgumentException("Длина раздела и дисковой области не может быть отрицательной.");
        }
        if (ntfs.bytesPerSector() <= 0) {
            throw new IllegalArgumentException("Размер сектора NTFS должен быть положительным.");
        }
        List<String> diagnostics = new ArrayList<>();
        if (volumeExtentBytes > partitionLengthBytes) {
            diagnostics.add("Размер дисковых областей тома превышает размер сопоставленного раздела.");
        }
        long extentSectors = volumeExtentBytes / ntfs.bytesPerSector();
        boolean sectorBoundaryMismatch = ntfs.numberOfSectors() != extentSectors;
        if (!sectorBoundaryMismatch) {
            if (fileSystem.totalBytes() > volumeExtentBytes) {
                diagnostics.add("Ёмкость файловой системы превышает суммарный размер дисковых областей тома.");
            } else if (fileSystem.totalBytes() < volumeExtentBytes) {
                diagnostics.add("Ёмкость файловой системы меньше размера сопоставленных областей; разницу могут "
                        + "объяснять метаданные, резервирование или политика файловой системы.");
            }
        }
        if (ntfs.bytesPerCluster() != fileSystem.clusterSizeBytes()) {
            diagnostics.add("Размер кластера NTFS отличается от указанного Windows размера кластера файловой системы.");
        }
        if (ntfs.bytesPerSector() != fileSystem.bytesPerSector()) {
            diagnostics.add("Размер сектора файловой системы NTFS отличается от значения GetDiskFreeSpaceW.");
        }
        if (!sectorBoundaryMismatch) {
            if (metrics.totalClusterCapacityBytes() > volumeExtentBytes) {
                diagnostics.add("Общая ёмкость кластеров NTFS превышает размер сопоставленных областей тома.");
            } else if (metrics.totalClusterCapacityBytes() < volumeExtentBytes) {
                diagnostics.add("Ёмкость кластеров NTFS меньше размера сопоставленных областей; разницу могут "
                        + "объяснять служебные данные или зарезервированные области файловой системы.");
            }
        }
        if (volumeExtentBytes % ntfs.bytesPerSector() != 0) {
            diagnostics.add("Длина сопоставленной области не кратна размеру сектора NTFS.");
        }
        if (bootSector != null && bootSector.totalSectors() != ntfs.numberOfSectors()) {
            diagnostics.add("NTFS BPB TotalSectors (" + bootSector.totalSectors()
                    + ") отличается от FSCTL NumberOfSectors (" + ntfs.numberOfSectors() + ").");
        }
        if (bootSector != null && bootSector.bytesPerSector() != ntfs.bytesPerSector()) {
            diagnostics.add("NTFS BPB BytesPerSector (" + bootSector.bytesPerSector()
                    + ") отличается от FSCTL BytesPerSector (" + ntfs.bytesPerSector() + ").");
        }
        if (bootSector != null
                && bootSector.fileRecordSizeBytes() != ntfs.bytesPerFileRecordSegment()) {
            diagnostics.add("Размер записи файла в NTFS BPB (" + bootSector.fileRecordSizeBytes()
                    + ") отличается от FSCTL BytesPerFileRecordSegment ("
                    + ntfs.bytesPerFileRecordSegment() + ").");
        }
        if (ntfs.numberOfSectors() != extentSectors) {
            diagnostics.add(describeBoundaryMismatch(ntfs, bootSector, extentSectors,
                    matchingTerminalBackupBootSector, matchingBpbBoundaryBootSector,
                    extentStartingAbsoluteDiskLba, backupEvidence, fileSystem, metrics,
                    volumeExtentBytes));
        }
        return List.copyOf(diagnostics);
    }

    private static String describeBoundaryMismatch(
            NtfsVolumeData ntfs,
            NtfsBootSectorInfo bootSector,
            long extentSectors,
            boolean matchingTerminalBackupBootSector,
            boolean matchingBpbBoundaryBootSector,
            Long extentStartingAbsoluteDiskLba,
            NtfsBackupBootSectorEvidence backupEvidence,
            VolumeFileSystemInfo fileSystem,
            NtfsMetrics metrics,
            long volumeExtentBytes) {
        Long difference;
        Long differenceBytes;
        try {
            difference = Math.subtractExact(extentSectors, ntfs.numberOfSectors());
            differenceBytes = Math.multiplyExact(difference, (long) ntfs.bytesPerSector());
        } catch (ArithmeticException e) {
            difference = null;
            differenceBytes = null;
        }
        StringBuilder message = new StringBuilder("Количество секторов NTFS отличается от области Windows: ")
                .append("FSCTL NumberOfSectors=").append(ntfs.numberOfSectors())
                .append(", BPB TotalSectors=")
                .append(bootSector == null ? "недоступно" : bootSector.totalSectors())
                .append(", секторов в области Windows=").append(extentSectors)
                .append(", разница (область минус NTFS)=");
        if (difference == null) {
            message.append("не вычислена из-за переполнения.");
        } else {
            message.append(difference).append(" логических секторов (")
                    .append(differenceBytes).append(" байт).");
        }
        message.append(" Windows сообщает ёмкость файловой системы ")
                .append(fileSystem.totalBytes()).append(" байт; суммарная ёмкость полных кластеров NTFS — ")
                .append(metrics.totalClusterCapacityBytes()).append(" байт.");
        try {
            message.append(" Разница области Windows и сообщённой ёмкости файловой системы — ")
                    .append(Math.subtractExact(volumeExtentBytes, fileSystem.totalBytes())).append(" байт.");
        } catch (ArithmeticException e) {
            message.append(" Разница объёма области и ёмкости файловой системы не вычислена из-за переполнения.");
        }
        if (bootSector != null) {
            message.append(" По BPB конечный сектор тома — LBA тома ")
                    .append(bootSector.totalSectors() - 1)
                    .append("; копия основной загрузочной записи в этой точке совпадает=")
                    .append(matchingBpbBoundaryBootSector).append(".");
            if (extentStartingAbsoluteDiskLba != null) {
                try {
                    long bpbLastAbsolute = Math.addExact(extentStartingAbsoluteDiskLba,
                            bootSector.totalSectors() - 1);
                    long extentLastRelative = extentSectors - 1;
                    long extentLastAbsolute = Math.addExact(extentStartingAbsoluteDiskLba,
                            extentLastRelative);
                    message.append(" Это абсолютный LBA физического диска ").append(bpbLastAbsolute)
                            .append("; последний сектор области — LBA тома ")
                            .append(extentLastRelative).append(" / абсолютный LBA физического диска ")
                            .append(extentLastAbsolute).append(".");
                } catch (ArithmeticException e) {
                    message.append(" Абсолютный LBA физического диска не вычислен из-за переполнения.");
                }
                if (difference != null && difference > 0
                        && difference == bootSector.sectorsPerCluster()) {
                    message.append(" Разница равна одному кластеру NTFS (")
                            .append(bootSector.sectorsPerCluster()).append(" секторов).");
                }
            }
        }
        message.append(" Копия основной записи на последнем секторе области совпадает=")
                .append(matchingTerminalBackupBootSector).append(".");
        if (backupEvidence != null) {
            message.append(' ').append(describeBackupEvidence(backupEvidence));
        }
        message.append(" Причина расхождения не установлена; предупреждение сохраняется. "
                + "Совпадение отдельной копии не доказывает корректность границы NTFS.");
        return message.toString();
    }

    private static String describeBackupEvidence(NtfsBackupBootSectorEvidence evidence) {
        return "Проверка хвостовых секторов (абсолютные LBA физического диска): сектор BPB "
                + evidence.bpbLastAbsoluteDiskLba()
                + (evidence.bpbLastSectorReadable()
                ? (evidence.bpbLastSectorMatchesPrimary()
                ? " совпадает с основной загрузочной записью. "
                : " не совпадает с основной загрузочной записью. ")
                : " не удалось прочитать. ")
                + "конечный сектор области — абсолютный LBA диска "
                + evidence.extentLastAbsoluteDiskLba()
                + ". Совпадающие копии найдены на LBA "
                + evidence.matchingTrailingAbsoluteDiskLbas() + "; нулевые абсолютные LBA: "
                + evidence.zeroTrailingAbsoluteDiskLbas() + "; прочие ненулевые абсолютные LBA: "
                + evidence.otherTrailingAbsoluteDiskLbas() + "; непрочитанные абсолютные LBA: "
                + evidence.unreadableTrailingAbsoluteDiskLbas() + ". "
                + (evidence.trailingInspectionComplete()
                ? "Хвостовая область прочитана полностью. "
                : "Проверка хвостовой области была ограничена или неполна. ")
                + "Совпадение отдельной копии не доказывает корректность границы NTFS.";
    }
}
