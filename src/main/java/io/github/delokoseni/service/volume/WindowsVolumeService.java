package io.github.delokoseni.service.volume;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import io.github.delokoseni.model.DiskExtent;
import io.github.delokoseni.model.NtfsMetrics;
import io.github.delokoseni.model.NtfsVolumeData;
import io.github.delokoseni.model.VolumeFileSystemInfo;
import io.github.delokoseni.model.VolumeInfo;
import io.github.delokoseni.model.VolumeInspection;
import io.github.delokoseni.service.disk.StorageDeviceIdentity;
import io.github.delokoseni.service.disk.DiskOpenException;
import io.github.delokoseni.service.disk.RawDiskAccessStatus;
import io.github.delokoseni.service.disk.WindowsDiskReader;
import io.github.delokoseni.service.disk.WindowsPrivilegeService;
import io.github.delokoseni.service.disk.WindowsPartitionInfo;
import io.github.delokoseni.service.disk.Win32ErrorNames;
import io.github.delokoseni.service.ntfs.FilesystemConsistencyService;
import io.github.delokoseni.service.ntfs.NtfsCalculationService;
import io.github.delokoseni.service.ntfs.NtfsBackupBootSectorEvidence;
import io.github.delokoseni.service.ntfs.NtfsBootSectorInfo;
import io.github.delokoseni.service.ntfs.NtfsBootSectorParser;
import io.github.delokoseni.service.ntfs.NtfsFileRecordSegmentSize;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

public final class WindowsVolumeService {
    private static final int GENERIC_READ = 0x80000000;
    private static final int FILE_SHARE_READ = 0x00000001;
    private static final int FILE_SHARE_WRITE = 0x00000002;
    private static final int FILE_SHARE_DELETE = 0x00000004;
    private static final int OPEN_EXISTING = 3;
    private static final long INVALID_HANDLE_VALUE = -1L;
    private static final int ERROR_NO_MORE_FILES = 18;
    private static final int ERROR_MORE_DATA = 234;

    private static final int IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS = 0x00560000;
    private static final int MAX_VOLUME_PATH_CHARS = 32_768;
    private static final int VOLUME_EXTENTS_BUFFER_SIZE = 256 * 1024;
    private static final int MAX_EXTENTS = 4096;

    public List<VolumeInspection> enumerate() throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            throw new IOException("Проверка томов доступна только в Windows.");
        }
        Kernel32 api = Kernel32Holder.INSTANCE;
        List<VolumeInspection> volumes = new ArrayList<>();
        char[] pathBuffer = new char[MAX_VOLUME_PATH_CHARS];
        Pointer search = api.FindFirstVolumeW(pathBuffer, pathBuffer.length);
        if (isInvalidHandle(search)) {
            throw win32Error(api, "Не удалось перечислить тома Windows");
        }
        try {
            do {
                String volumePath = cString(pathBuffer);
                try {
                    volumes.add(inspectOne(api, volumePath));
                } catch (RuntimeException e) {
                    volumes.add(unavailableVolume(volumePath, e));
                }
                java.util.Arrays.fill(pathBuffer, '\0');
            } while (api.FindNextVolumeW(search, pathBuffer, pathBuffer.length));
            int error = api.GetLastError();
            if (error != ERROR_NO_MORE_FILES) {
                VolumeInfo partial = new VolumeInfo("<volume enumeration incomplete>",
                        List.of(), "", "", 0, 0, 0);
                volumes.add(new VolumeInspection(partial, Optional.empty(), List.of(), Optional.empty(),
                        List.of("FindNextVolumeW завершился неожиданно (ошибка Win32 " + error + " "
                                + Win32ErrorNames.name(error) + ").")));
            }
        } finally {
            if (!api.FindVolumeClose(search)) {
                VolumeInfo partial = new VolumeInfo("<volume enumeration cleanup warning>",
                        List.of(), "", "", 0, 0, 0);
                volumes.add(new VolumeInspection(partial, Optional.empty(), List.of(), Optional.empty(),
                        List.of(closeVolumeError(api))));
            }
        }
        return List.copyOf(volumes);
    }

    public VolumeAnalysisCandidate verifySelectedVolume(VolumeInspection requested) throws IOException {
        if (requested == null || requested.volume().volumeGuidPath().isBlank()) {
            throw new IOException("Выбранный том не имеет допустимого идентификатора.");
        }
        String selectedPath = requested.volume().volumeGuidPath();
        VolumeInspection volume;
        try {
            volume = new VolumeSelectionService().resolveCurrentSelection(enumerate(), selectedPath);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        int diskNumber = volume.diskExtents().getFirst().diskNumber();
        try (WindowsDiskReader reader = WindowsDiskReader.open(diskNumber)) {
            List<String> verificationDiagnostics = new ArrayList<>();
            String model = "";
            String serialNumber = "";
            try {
                StorageDeviceIdentity identity = reader.readStorageDeviceIdentity();
                model = identity.modelName();
                serialNumber = identity.serialNumber();
            } catch (IOException e) {
                verificationDiagnostics.add("Модель и идентификатор диска недоступны: " + e.getMessage());
            }
            if (reader.getDiskSize() <= 0) {
                throw new IOException("Ёмкость сопоставленного физического диска недоступна.");
            }
            OptionalInt physicalSectorSize;
            try {
                physicalSectorSize = OptionalInt.of(reader.readPhysicalSectorSize());
            } catch (IOException e) {
                physicalSectorSize = OptionalInt.empty();
                verificationDiagnostics.add("Размер физического сектора не удалось получить с устройства: "
                        + e.getMessage());
            }

            List<WindowsPartitionInfo> windowsPartitions = List.of();
            try {
                windowsPartitions = reader.readWindowsPartitionLayout().partitions();
            } catch (IOException e) {
                verificationDiagnostics.add("Разметка разделов Windows недоступна; NTFS-запрос по GUID тома "
                        + "может выполняться независимо: " + e.getMessage());
            }
            List<WindowsPartitionInfo> verifiedPartitions = List.of();
            if (!windowsPartitions.isEmpty()) {
                Map<Integer, List<WindowsPartitionInfo>> partitionsByDisk = Map.of(diskNumber, windowsPartitions);
                List<VolumePartitionMappingService.ExtentPartitionMapping> mappings =
                        new VolumePartitionMappingService().map(volume.diskExtents(), partitionsByDisk);
                if (mappings.size() == volume.diskExtents().size()
                        && mappings.stream().allMatch(VolumePartitionMappingService.ExtentPartitionMapping::isUnambiguous)) {
                    Map<Integer, WindowsPartitionInfo> uniquePartitions = new LinkedHashMap<>();
                    for (var mapping : mappings) {
                        WindowsPartitionInfo partition = mapping.candidatePartitions().getFirst();
                        uniquePartitions.put(partition.partitionNumber(), partition);
                    }
                    verifiedPartitions = List.copyOf(uniquePartitions.values());
                } else {
                    verificationDiagnostics.add("Область тома не сопоставлена однозначно с разметкой Windows; "
                            + "сведения о разделе не подтверждены. Запрос NTFS по GUID тома не зависит от GPT.");
                }
            } else {
                verificationDiagnostics.add("Разметка Windows не содержит подтверждённых разделов; "
                        + "сведения о разделе недоступны. Запрос NTFS по GUID тома не зависит от GPT.");
            }
            return new VolumeAnalysisCandidate(volume, diskNumber, model, serialNumber, reader.getDiskSize(),
                    reader.getLogicalSectorSize(), physicalSectorSize,
                    verifiedPartitions, verificationDiagnostics);
        } catch (DiskOpenException e) {
            String message = e.getMessage();
            if (RawDiskAccessStatus.fromWin32Error(e.win32Error())
                    == RawDiskAccessStatus.ACCESS_DENIED) {
                try {
                    message += " " + new WindowsPrivilegeService().accessDeniedGuidance();
                } catch (IOException privilegeError) {
                    message += " Не удалось определить статус администратора: "
                            + privilegeError.getMessage();
                }
            }
            throw new IOException(message, e);
        }
    }

    public VolumeAnalysis inspectVerifiedVolume(
            VolumeAnalysisCandidate candidate, boolean userConfirmed) throws IOException {
        if (candidate == null) {
            throw new IllegalArgumentException("Требуется проверенный выбранный том.");
        }
        if (!userConfirmed) {
            throw new IOException("Анализ отменён: явное подтверждение пользователя не получено.");
        }

        VolumeAnalysisCandidate fresh = verifySelectedVolume(candidate.volumeInspection());
        if (!sameCandidate(candidate, fresh)) {
            throw new IOException("Идентификатор выбранного устройства, ёмкость или карта разделов изменились; "
                    + "анализ отменён.");
        }
        String fileSystem = fresh.volumeInspection().volume().fileSystem();
        FileSystemType fileSystemType = FileSystemType.fromWindowsName(fileSystem);
        List<String> diagnostics = new ArrayList<>(fresh.diagnostics());
        if (!NtfsAnalysisPolicy.shouldQuery(fileSystem, userConfirmed)) {
            return new VolumeAnalysis(fresh, Optional.empty(), Optional.empty(), Optional.empty(),
                    append(diagnostics, "Анализ NTFS недоступен для файловой системы "
                            + (fileSystemType == FileSystemType.UNKNOWN ? "неизвестного типа" : fileSystem) + "."));
        }

        Kernel32 api = Kernel32Holder.INSTANCE;
        Pointer handle = openVolume(api, fresh.volumeInspection().volume().volumeGuidPath(),
                GENERIC_READ, diagnostics, "read-only NTFS volume-data query");
        if (handle == null) {
            throw new IOException("Не удалось открыть выбранный том для чтения: "
                    + String.join("; ", diagnostics));
        }
        try {
            NtfsVolumeData data = readNtfsData(api, handle);
            NtfsMetrics metrics;
            try {
                metrics = new NtfsCalculationService().calculate(data);
            } catch (IllegalArgumentException e) {
                throw new IOException("Проверка параметров NTFS завершилась ошибкой: " + e.getMessage(), e);
            }
            NtfsBootSectorInfo bootSector = null;
            NtfsBackupBootSectorEvidence backupEvidence = null;
            long extentStartingLba = 0;
            try {
                    if (fresh.volumeInspection().diskExtents().size() == 1
                            && fresh.partitions().size() == 1) {
                        BootSectorCheck check = readNtfsBootSector(fresh);
                        bootSector = check.info();
                        backupEvidence = check.backupEvidence();
                        extentStartingLba = check.extentStartingLba();
                        NtfsFileRecordSegmentSize.validateWindowsClusterCount(
                                bootSector.fileRecordSizeBytes(), data.bytesPerCluster(),
                                data.clustersPerFileRecordSegment());
                    } else {
                        diagnostics.add("Проверка загрузочного сектора NTFS пропущена: том содержит "
                                + "несколько дисковых областей или разделов.");
                    }
            } catch (IOException | IllegalArgumentException e) {
                diagnostics.add("Не удалось проверить геометрию загрузочного сектора/записи NTFS: "
                        + e.getMessage());
            }
            long extentBytes = sumExtentBytes(fresh.volumeInspection().diskExtents());
            long partitionBytes = sumPartitionBytes(fresh.partitions());
            if (fresh.volumeInspection().fileSystem().isPresent()) {
                VolumeFileSystemInfo fileSystemInfo = fresh.volumeInspection().fileSystem().orElseThrow();
                diagnostics.addAll(new FilesystemConsistencyService().validate(
                        partitionBytes, extentBytes, fileSystemInfo, data, metrics,
                        bootSector, backupEvidence,
                        extentStartingLba));
            } else {
                diagnostics.add("Сверка с геометрией файловой системы Windows пропущена: эти сведения недоступны.");
            }
            return new VolumeAnalysis(fresh, Optional.of(data), Optional.of(metrics),
                    Optional.ofNullable(bootSector), diagnostics);
        } finally {
            if (!api.CloseHandle(handle)) {
                throw win32Error(api, "Не удалось закрыть дескриптор выбранного тома");
            }
        }
    }

    private static BootSectorCheck readNtfsBootSector(VolumeAnalysisCandidate candidate)
            throws IOException {
        List<DiskExtent> extents = candidate.volumeInspection().diskExtents();
        if (extents.size() != 1) {
            throw new IOException("Ожидалась одна непрерывная область тома Windows, получено: " + extents.size());
        }
        DiskExtent extent = extents.getFirst();
        if (extent.diskNumber() != candidate.physicalDiskNumber()
                || candidate.partitions().stream().noneMatch(partition ->
                partition.startingOffsetBytes() == extent.startingOffsetBytes()
                        && partition.lengthBytes() == extent.extentLengthBytes())) {
            throw new IOException("Проверенная область NTFS больше не соответствует выбранному разделу.");
        }
        try (WindowsDiskReader reader = WindowsDiskReader.open(extent.diskNumber())) {
            if (reader.getDiskSize() != candidate.diskCapacityBytes()) {
                throw new IOException("Ёмкость физического диска изменилась при проверке загрузочного сектора NTFS.");
            }
            if (!candidate.model().isBlank() || !candidate.serialNumber().isBlank()) {
                StorageDeviceIdentity identity;
                try {
                    identity = reader.readStorageDeviceIdentity();
                } catch (IOException e) {
                    throw new IOException("Не удалось повторно проверить идентификатор физического диска.", e);
                }
                if ((!candidate.model().isBlank() && !candidate.model().equalsIgnoreCase(identity.modelName()))
                        || (!candidate.serialNumber().isBlank()
                        && !candidate.serialNumber().equalsIgnoreCase(identity.serialNumber()))) {
                    throw new IOException("Идентификатор физического диска изменился при проверке загрузочного сектора NTFS.");
                }
            }
            int sectorSize = reader.getLogicalSectorSize();
            if (extent.startingOffsetBytes() < 0 || extent.startingOffsetBytes() % sectorSize != 0
                    || extent.extentLengthBytes() < sectorSize
                    || extent.extentLengthBytes() % sectorSize != 0) {
                throw new IOException("Область тома NTFS имеет недопустимые границы секторов.");
            }
            long lastSectorOffset;
            try {
                lastSectorOffset = Math.addExact(extent.startingOffsetBytes(),
                        Math.subtractExact(extent.extentLengthBytes(), sectorSize));
            } catch (ArithmeticException e) {
                throw new IOException("Смещение загрузочного сектора NTFS выходит за допустимые границы.", e);
            }
            byte[] primary = reader.read(extent.startingOffsetBytes(), sectorSize);
            NtfsBootSectorInfo info = new NtfsBootSectorParser().parse(primary);
            if (info.bytesPerSector() != sectorSize) {
                throw new IOException("NTFS BPB BytesPerSector=" + info.bytesPerSector()
                        + " не совпадает с размером логического сектора диска " + sectorSize + ".");
            }
            long extentSectors = extent.extentLengthBytes() / sectorSize;
            long extentStartingLba = extent.startingOffsetBytes() / sectorSize;
            long extentLastLba = Math.addExact(extentStartingLba, extentSectors - 1);
            byte[] terminalBackup = reader.read(lastSectorOffset, sectorSize);
            long bpbExpectedBackupLba = -1;
            boolean bpbBoundaryReadable = false;
            boolean matchingBpbBoundaryCopy = false;
            List<Long> matchingTrailingLbas = new ArrayList<>();
            List<Long> zeroTrailingLbas = new ArrayList<>();
            List<Long> otherTrailingLbas = new ArrayList<>();
            List<Long> unreadableTrailingLbas = new ArrayList<>();
            boolean trailingInspectionComplete = false;
            bpbExpectedBackupLba = Math.addExact(extentStartingLba, info.totalSectors() - 1);
            if (info.totalSectors() <= extentSectors) {
                long bpbBackupOffset = Math.multiplyExact(bpbExpectedBackupLba, (long) sectorSize);
                try {
                    byte[] bpbBoundary = bpbBackupOffset == lastSectorOffset
                            ? terminalBackup : reader.read(bpbBackupOffset, sectorSize);
                    bpbBoundaryReadable = true;
                    matchingBpbBoundaryCopy = Arrays.equals(primary, bpbBoundary);
                } catch (IOException ignored) {
                    bpbBoundaryReadable = false;
                }
                long trailingSectors = extentLastLba - bpbExpectedBackupLba;
                if (trailingSectors <= 4096) {
                    trailingInspectionComplete = true;
                    for (long lba = bpbExpectedBackupLba + 1; lba <= extentLastLba; lba++) {
                        try {
                            long offset = Math.multiplyExact(lba, (long) sectorSize);
                            byte[] sector = reader.read(offset, sectorSize);
                            if (Arrays.equals(primary, sector)) {
                                matchingTrailingLbas.add(lba);
                            } else if (isZeroFilled(sector)) {
                                zeroTrailingLbas.add(lba);
                            } else {
                                otherTrailingLbas.add(lba);
                            }
                        } catch (IOException e) {
                            unreadableTrailingLbas.add(lba);
                            trailingInspectionComplete = false;
                        }
                    }
                }
            }
            NtfsBackupBootSectorEvidence backupEvidence = new NtfsBackupBootSectorEvidence(
                    bpbExpectedBackupLba, extentLastLba,
                    bpbBoundaryReadable, matchingBpbBoundaryCopy, matchingTrailingLbas,
                    zeroTrailingLbas, otherTrailingLbas, unreadableTrailingLbas,
                    trailingInspectionComplete);
            return new BootSectorCheck(info, backupEvidence, extentStartingLba);
        }
    }

    private static boolean isZeroFilled(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static long sumExtentBytes(List<DiskExtent> extents) throws IOException {
        long total = 0;
        try {
            for (DiskExtent extent : extents) {
                total = Math.addExact(total, extent.extentLengthBytes());
            }
            return total;
        } catch (ArithmeticException e) {
            throw new IOException("Переполнение размера сопоставленных областей тома.", e);
        }
    }

    private static List<String> append(List<String> messages, String message) {
        List<String> result = new ArrayList<>(messages);
        result.add(message);
        return List.copyOf(result);
    }

    private static long sumPartitionBytes(List<WindowsPartitionInfo> partitions) throws IOException {
        long total = 0;
        try {
            for (WindowsPartitionInfo partition : partitions) {
                total = Math.addExact(total, partition.lengthBytes());
            }
            return total;
        } catch (ArithmeticException e) {
            throw new IOException("Переполнение размера сопоставленных разделов.", e);
        }
    }

    private static boolean sameCandidate(VolumeAnalysisCandidate first, VolumeAnalysisCandidate second) {
        return first.physicalDiskNumber() == second.physicalDiskNumber()
                && first.diskCapacityBytes() == second.diskCapacityBytes()
                && first.logicalSectorSize() == second.logicalSectorSize()
                && first.physicalSectorSize().equals(second.physicalSectorSize())
                && first.model().equalsIgnoreCase(second.model())
                && first.serialNumber().equalsIgnoreCase(second.serialNumber())
                && first.volumeInspection().volume().volumeGuidPath()
                .equalsIgnoreCase(second.volumeInspection().volume().volumeGuidPath())
                && first.volumeInspection().volume().mountPoints()
                .equals(second.volumeInspection().volume().mountPoints())
                && first.volumeInspection().volume().fileSystem()
                .equalsIgnoreCase(second.volumeInspection().volume().fileSystem())
                && first.volumeInspection().fileSystem().map(VolumeFileSystemInfo::volumeSerialNumber)
                .equals(second.volumeInspection().fileSystem()
                        .map(VolumeFileSystemInfo::volumeSerialNumber))
                && first.volumeInspection().diskExtents().equals(second.volumeInspection().diskExtents())
                && first.partitions().equals(second.partitions());
    }

    private static VolumeInspection inspectOne(Kernel32 api, String volumePath) {
        List<String> diagnostics = new ArrayList<>();
        List<String> mountPoints = new ArrayList<>();
        Optional<VolumeFileSystemInfo> fileSystem = Optional.empty();
        List<DiskExtent> extents = List.of();
        Optional<NtfsVolumeData> ntfs = Optional.empty();
        long capacity = 0;
        long available = 0;
        long clusterSize = 0;
        long volumeSerial = 0;
        String label = "";
        String fileSystemName = "";
        long maxComponentLength = 0;
        long capabilities = 0;
        int sectorsPerCluster = 0;
        int bytesPerSector = 0;

        try {
            mountPoints.addAll(getMountPoints(api, volumePath));
        } catch (IOException e) {
            diagnostics.add(e.getMessage());
        }
        try {
            char[] labelBuffer = new char[1024];
            char[] fsBuffer = new char[256];
            IntByReference serial = new IntByReference();
            IntByReference maxComponent = new IntByReference();
            IntByReference flags = new IntByReference();
            if (!api.GetVolumeInformationW(volumePath, labelBuffer, labelBuffer.length, serial,
                    maxComponent, flags, fsBuffer, fsBuffer.length)) {
                throw win32Error(api, "Сбой GetVolumeInformationW для " + volumePath);
            }
            label = cString(labelBuffer);
            fileSystemName = cString(fsBuffer);
            volumeSerial = Integer.toUnsignedLong(serial.getValue());
            maxComponentLength = Integer.toUnsignedLong(maxComponent.getValue());
            capabilities = Integer.toUnsignedLong(flags.getValue());

            LongByReference freeAvailable = new LongByReference();
            LongByReference total = new LongByReference();
            LongByReference totalFree = new LongByReference();
            if (!api.GetDiskFreeSpaceExW(volumePath, freeAvailable, total, totalFree)) {
                throw win32Error(api, "Сбой GetDiskFreeSpaceExW для " + volumePath);
            }
            capacity = checkedUnsignedLong(total.getValue(), "volume capacity");
            available = checkedUnsignedLong(freeAvailable.getValue(), "available volume capacity");
            IntByReference sectorsPerClusterRef = new IntByReference();
            IntByReference bytesPerSectorRef = new IntByReference();
            IntByReference freeClustersRef = new IntByReference();
            IntByReference totalClustersRef = new IntByReference();
            if (!api.GetDiskFreeSpaceW(volumePath, sectorsPerClusterRef, bytesPerSectorRef,
                    freeClustersRef, totalClustersRef)) {
                throw win32Error(api, "Сбой GetDiskFreeSpaceW для " + volumePath);
            }
            sectorsPerCluster = sectorsPerClusterRef.getValue();
            bytesPerSector = bytesPerSectorRef.getValue();
            if (sectorsPerCluster <= 0 || bytesPerSector <= 0) {
                throw new IOException("Windows вернула недопустимую геометрию кластеров файловой системы.");
            }
            clusterSize = Math.multiplyExact((long) sectorsPerCluster, bytesPerSector);
            long totalFreeBytes = checkedUnsignedLong(totalFree.getValue(), "free volume capacity");
            if (totalFreeBytes > capacity || available > totalFreeBytes) {
                throw new IOException("Windows вернула несогласованные значения общей, свободной и доступной ёмкости тома.");
            }
            fileSystem = Optional.of(new VolumeFileSystemInfo(fileSystemName, label, volumeSerial,
                    maxComponentLength, capabilities, capacity, totalFreeBytes, available,
                    sectorsPerCluster, bytesPerSector));
        } catch (IOException | ArithmeticException | IllegalArgumentException e) {
            diagnostics.add("Сведения о файловой системе недоступны для " + volumePath + ": " + e.getMessage());
            capacity = 0;
            available = 0;
            clusterSize = 0;
        }

        Pointer volumeHandle = openVolume(api, volumePath, 0,
                diagnostics, "volume extent metadata query");
        if (volumeHandle != null) {
            try {
                try {
                    extents = readVolumeExtents(api, volumeHandle);
                } catch (IOException e) {
                    diagnostics.add("Не удалось сопоставить области тома " + volumePath + ": " + e.getMessage());
                }
            } finally {
                if (!api.CloseHandle(volumeHandle)) {
                    diagnostics.add(win32Error(api, "Не удалось закрыть дескриптор тома").getMessage());
                }
            }
        }

        VolumeInfo volume = new VolumeInfo(volumePath, mountPoints, fileSystemName, label,
                capacity, available, clusterSize);
        return new VolumeInspection(volume, fileSystem, extents, ntfs, diagnostics);
    }

    private static List<String> getMountPoints(Kernel32 api, String volumePath) throws IOException {
        IntByReference required = new IntByReference();
        api.GetVolumePathNamesForVolumeNameW(volumePath, null, 0, required);
        int error = api.GetLastError();
        if (error != ERROR_MORE_DATA && error != 0) {
            throw new IOException("Сбой GetVolumePathNamesForVolumeNameW (ошибка Win32 " + error + " "
                    + Win32ErrorNames.name(error) + ").");
        }
        int chars = required.getValue();
        if (chars <= 0) {
            return List.of();
        }
        if (chars > MAX_VOLUME_PATH_CHARS) {
            throw new IOException("Список точек подключения тома превышает ограничение безопасности.");
        }
        char[] buffer = new char[chars];
        if (!api.GetVolumePathNamesForVolumeNameW(volumePath, buffer, buffer.length, required)) {
            throw win32Error(api, "Не удалось получить точки подключения для " + volumePath);
        }
        List<String> paths = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < buffer.length; i++) {
            if (buffer[i] == '\0') {
                if (i > start) {
                    paths.add(new String(buffer, start, i - start));
                } else if (start == i) {
                    break;
                }
                start = i + 1;
            }
        }
        return List.copyOf(paths);
    }

    private static Pointer openVolume(
            Kernel32 api, String volumePath, int desiredAccess,
            List<String> diagnostics, String purpose) {
        String openPath = volumePath.endsWith("\\")
                ? volumePath.substring(0, volumePath.length() - 1) : volumePath;
        int shareMode = FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE;
        Pointer handle = api.CreateFileW(openPath, desiredAccess,
                shareMode, null, OPEN_EXISTING, 0, null);
        if (isInvalidHandle(handle)) {
            int error = api.GetLastError();
            diagnostics.add("Не удалось выполнить CreateFileW для операции «" + purpose + "» по адресу " + openPath
                    + " (desiredAccess=0x" + Integer.toHexString(desiredAccess)
                    + ", shareMode=0x" + Integer.toHexString(shareMode)
                    + ", creationDisposition=" + OPEN_EXISTING
                    + ", flagsAndAttributes=0, ошибка Win32 " + error + " "
                    + Win32ErrorNames.name(error) + ")");
            return null;
        }
        return handle;
    }

    private static VolumeInspection unavailableVolume(String volumePath, RuntimeException error) {
        VolumeInfo volume = new VolumeInfo(volumePath, List.of(), "", "", 0, 0, 0);
        return new VolumeInspection(volume, Optional.empty(), List.of(), Optional.empty(),
                List.of("Не удалось проверить том: " + error.getMessage()));
    }

    private static List<DiskExtent> readVolumeExtents(Kernel32 api, Pointer handle) throws IOException {
        if (Native.POINTER_SIZE != 8) {
            throw new IOException("Декодирование областей тома поддерживается только в 64-разрядном процессе Windows.");
        }
        byte[] buffer = new byte[VOLUME_EXTENTS_BUFFER_SIZE];
        IntByReference bytesReturned = new IntByReference();
        if (!api.DeviceIoControl(handle, IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS,
                null, 0, buffer, buffer.length, bytesReturned, null)) {
            throw win32Error(api, "Сбой IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS "
                    + "(доступ к дескриптору тома desiredAccess=0, совместный доступ READ|WRITE|DELETE)");
        }
        if (bytesReturned.getValue() < 4 || bytesReturned.getValue() > buffer.length) {
            throw new IOException("Windows вернула недопустимую длину ответа с областями тома.");
        }
        int count = i32(buffer, 0);
        if (count < 0 || count > MAX_EXTENTS) {
            throw new IOException("Количество областей тома недопустимо или превышает ограничение безопасности.");
        }
        int firstExtentOffset = 8;
        int extentSize = 24;
        long required = (long) firstExtentOffset + (long) count * extentSize;
        if (required > bytesReturned.getValue()) {
            throw new IOException("Windows вернула неполный массив дисковых областей тома.");
        }
        List<DiskExtent> extents = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int offset = firstExtentOffset + i * extentSize;
            int diskNumber = i32(buffer, offset);
            long start = i64(buffer, offset + 8);
            long length = i64(buffer, offset + 16);
            if (diskNumber < 0 || start < 0 || length < 0) {
                throw new IOException("Windows вернула недопустимые значения дисковой области тома.");
            }
            try {
                Math.addExact(start, length);
            } catch (ArithmeticException e) {
                throw new IOException("Граница дисковой области тома выходит за допустимые пределы.", e);
            }
            extents.add(new DiskExtent(diskNumber, start, length));
        }
        return List.copyOf(extents);
    }

    private static NtfsVolumeData readNtfsData(Kernel32 api, Pointer handle) throws IOException {
        return NtfsVolumeDataQuery.read((buffer, bytesReturned) ->
                        api.DeviceIoControl(handle, NtfsVolumeDataQuery.FSCTL_GET_NTFS_VOLUME_DATA,
                                null, 0, buffer, buffer.length, bytesReturned, null),
                api::GetLastError);
    }

    private static long checkedUnsignedLong(long value, String field) throws IOException {
        if (value < 0) {
            throw new IOException("Windows вернула значение поля «" + field
                    + "» за пределами поддерживаемого знакового 64-разрядного диапазона.");
        }
        return value;
    }

    private static boolean isInvalidHandle(Pointer handle) {
        if (handle == null) {
            return true;
        }
        long value = Pointer.nativeValue(handle);
        return value == INVALID_HANDLE_VALUE || value == 0xffff_ffffL || value == 0;
    }

    private static String cString(char[] buffer) {
        int length = 0;
        while (length < buffer.length && buffer[length] != '\0') {
            length++;
        }
        return new String(buffer, 0, length);
    }

    private static int i32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static long i64(byte[] bytes, int offset) {
        long low = Integer.toUnsignedLong(i32(bytes, offset));
        long high = Integer.toUnsignedLong(i32(bytes, offset + 4));
        long value = (high << 32) | low;
        if (value < 0) {
            throw new IllegalArgumentException("Беззнаковое значение превышает знаковый 64-разрядный диапазон.");
        }
        return value;
    }

    private static IOException win32Error(Kernel32 api, String message) {
        int error = api.GetLastError();
        return new IOException(message + " (ошибка Win32 " + error + " "
                + Win32ErrorNames.name(error) + ")");
    }

    private static String closeVolumeError(Kernel32 api) {
        int error = api.GetLastError();
        return "Сбой FindVolumeClose (ошибка Win32 " + error + " " + Win32ErrorNames.name(error) + ").";
    }

    private record BootSectorCheck(
            NtfsBootSectorInfo info,
            NtfsBackupBootSectorEvidence backupEvidence,
            long extentStartingLba) {
    }

    private interface Kernel32 extends StdCallLibrary {
        Pointer FindFirstVolumeW(char[] volumeName, int bufferLength);

        boolean FindNextVolumeW(Pointer findHandle, char[] volumeName, int bufferLength);

        boolean FindVolumeClose(Pointer findHandle);

        boolean GetVolumePathNamesForVolumeNameW(String volumeName, char[] paths, int bufferLength,
                                                 IntByReference returnLength);

        boolean GetVolumeInformationW(String rootPath, char[] volumeName, int volumeNameSize,
                                      IntByReference volumeSerial, IntByReference maxComponentLength,
                                      IntByReference fileSystemFlags, char[] fileSystemName,
                                      int fileSystemNameSize);

        boolean GetDiskFreeSpaceExW(String directoryName, LongByReference freeBytesAvailable,
                                    LongByReference totalBytes, LongByReference totalFreeBytes);

        boolean GetDiskFreeSpaceW(String rootPathName, IntByReference sectorsPerCluster,
                                  IntByReference bytesPerSector, IntByReference numberOfFreeClusters,
                                  IntByReference totalNumberOfClusters);

        Pointer CreateFileW(String fileName, int desiredAccess, int shareMode, Pointer securityAttributes,
                            int creationDisposition, int flagsAndAttributes, Pointer templateFile);

        boolean DeviceIoControl(Pointer device, int controlCode, byte[] input, int inputSize,
                                byte[] output, int outputSize, IntByReference bytesReturned, Pointer overlapped);

        boolean CloseHandle(Pointer object);

        int GetLastError();
    }

    private static final class Kernel32Holder {
        private static final Kernel32 INSTANCE =
                Native.load("kernel32", Kernel32.class, W32APIOptions.UNICODE_OPTIONS);
    }
}
