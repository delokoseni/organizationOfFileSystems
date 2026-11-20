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
import io.github.delokoseni.service.ntfs.FilesystemConsistencyService;
import io.github.delokoseni.service.ntfs.NtfsCalculationService;

import java.io.IOException;
import java.util.ArrayList;
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
            throw new IOException("Volume inspection is available only on Windows");
        }
        Kernel32 api = Kernel32Holder.INSTANCE;
        List<VolumeInspection> volumes = new ArrayList<>();
        char[] pathBuffer = new char[MAX_VOLUME_PATH_CHARS];
        Pointer search = api.FindFirstVolumeW(pathBuffer, pathBuffer.length);
        if (isInvalidHandle(search)) {
            throw win32Error(api, "Could not enumerate Windows volumes");
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
                        List.of("FindNextVolumeW stopped unexpectedly (Win32 error " + error + ")")));
            }
        } finally {
            if (!api.FindVolumeClose(search)) {
                VolumeInfo partial = new VolumeInfo("<volume enumeration cleanup warning>",
                        List.of(), "", "", 0, 0, 0);
                volumes.add(new VolumeInspection(partial, Optional.empty(), List.of(), Optional.empty(),
                        List.of("FindVolumeClose failed (Win32 error " + api.GetLastError() + ")")));
            }
        }
        return List.copyOf(volumes);
    }

    public ExternalVolumeCandidate verifyExternalAdataD() throws IOException {
        return verifyExternalAdataD(enumerate());
    }

    public ExternalVolumeCandidate verifyExternalAdataD(List<VolumeInspection> volumes) throws IOException {
        ExternalVolumeSelection selection;
        try {
            selection = new ExternalVolumeSelectionService().selectDVolume(volumes);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        VolumeInspection volume = selection.volume();
        int diskNumber = selection.physicalDiskNumber();
        try (WindowsDiskReader reader = WindowsDiskReader.open(diskNumber)) {
            StorageDeviceIdentity identity = reader.readStorageDeviceIdentity();
            String model = identity.modelName();
            if (!"ADATA SE760".equalsIgnoreCase(model)) {
                throw new IOException("D: maps to physical disk " + diskNumber + " with model '"
                        + (model.isBlank() ? "unavailable" : model)
                        + "', not exactly ADATA SE760. Analysis is disabled.");
            }
            if (reader.getDiskSize() <= 0) {
                throw new IOException("Mapped physical disk capacity is unavailable.");
            }
            OptionalInt physicalSectorSize;
            try {
                physicalSectorSize = OptionalInt.of(reader.readPhysicalSectorSize());
            } catch (IOException e) {
                physicalSectorSize = OptionalInt.empty();
            }

            var layout = reader.readWindowsPartitionLayout();
            Map<Integer, List<WindowsPartitionInfo>> partitionsByDisk = Map.of(diskNumber, layout.partitions());
            List<VolumePartitionMappingService.ExtentPartitionMapping> mappings =
                    new VolumePartitionMappingService().map(volume.diskExtents(), partitionsByDisk);
            if (mappings.size() != volume.diskExtents().size()
                    || mappings.stream().anyMatch(mapping -> !mapping.isUnambiguous())) {
                throw new IOException("D: extents do not map unambiguously to Windows partitions; "
                        + "analysis is disabled.");
            }
            Map<Integer, WindowsPartitionInfo> uniquePartitions = new LinkedHashMap<>();
            for (var mapping : mappings) {
                WindowsPartitionInfo partition = mapping.candidatePartitions().getFirst();
                uniquePartitions.put(partition.partitionNumber(), partition);
            }
            return new ExternalVolumeCandidate(volume, diskNumber, model, identity.serialNumber(), reader.getDiskSize(),
                    reader.getLogicalSectorSize(), physicalSectorSize,
                    List.copyOf(uniquePartitions.values()));
        } catch (DiskOpenException e) {
            String message = e.getMessage();
            if (RawDiskAccessStatus.fromWin32Error(e.win32Error())
                    == RawDiskAccessStatus.ACCESS_DENIED) {
                try {
                    message += " " + new WindowsPrivilegeService().accessDeniedGuidance();
                } catch (IOException privilegeError) {
                    message += " Administrator status could not be determined: "
                            + privilegeError.getMessage();
                }
            }
            throw new IOException(message, e);
        }
    }

    public ExternalVolumeAnalysis inspectVerifiedAdataD(
            ExternalVolumeCandidate candidate, boolean userConfirmed) throws IOException {
        if (candidate == null) {
            throw new IllegalArgumentException("A verified ADATA volume candidate is required");
        }
        if (!userConfirmed) {
            throw new IOException("Analysis cancelled because explicit user confirmation was not supplied.");
        }

        ExternalVolumeCandidate fresh = verifyExternalAdataD();
        if (!sameCandidate(candidate, fresh)) {
            throw new IOException("D: device identity, capacity, or partition mapping changed; "
                    + "analysis is cancelled.");
        }
        String fileSystem = fresh.volumeInspection().volume().fileSystem();
        FileSystemType fileSystemType = FileSystemType.fromWindowsName(fileSystem);
        if (!NtfsAnalysisPolicy.shouldQuery(fileSystem, userConfirmed)) {
            return new ExternalVolumeAnalysis(fresh, Optional.empty(), Optional.empty(),
                    List.of("NTFS-specific analysis is unavailable for filesystem "
                            + (fileSystemType == FileSystemType.UNKNOWN ? "unknown" : fileSystem) + "."));
        }

        Kernel32 api = Kernel32Holder.INSTANCE;
        List<String> diagnostics = new ArrayList<>();
        Pointer handle = openVolume(api, fresh.volumeInspection().volume().volumeGuidPath(),
                GENERIC_READ, diagnostics, "read-only NTFS volume-data query");
        if (handle == null) {
            throw new IOException("Could not open verified ADATA volume read-only: "
                    + String.join("; ", diagnostics));
        }
        try {
            NtfsVolumeData data = readNtfsData(api, handle);
            NtfsMetrics metrics;
            try {
                metrics = new NtfsCalculationService().calculate(data);
            } catch (IllegalArgumentException e) {
                throw new IOException("NTFS parameters failed validation: " + e.getMessage(), e);
            }
            long extentBytes = sumExtentBytes(fresh.volumeInspection().diskExtents());
            long partitionBytes = sumPartitionBytes(fresh.partitions());
            VolumeFileSystemInfo fileSystemInfo = fresh.volumeInspection().fileSystem().orElseThrow(
                    () -> new IOException("Verified filesystem metadata is no longer available"));
            diagnostics.addAll(new FilesystemConsistencyService().validate(
                    partitionBytes, extentBytes, fileSystemInfo, data, metrics));
            return new ExternalVolumeAnalysis(fresh, Optional.of(data), Optional.of(metrics), diagnostics);
        } finally {
            if (!api.CloseHandle(handle)) {
                throw win32Error(api, "Could not close verified ADATA volume handle");
            }
        }
    }

    private static long sumExtentBytes(List<DiskExtent> extents) throws IOException {
        long total = 0;
        try {
            for (DiskExtent extent : extents) {
                total = Math.addExact(total, extent.extentLengthBytes());
            }
            return total;
        } catch (ArithmeticException e) {
            throw new IOException("Mapped volume extent capacity overflows", e);
        }
    }

    private static long sumPartitionBytes(List<WindowsPartitionInfo> partitions) throws IOException {
        long total = 0;
        try {
            for (WindowsPartitionInfo partition : partitions) {
                total = Math.addExact(total, partition.lengthBytes());
            }
            return total;
        } catch (ArithmeticException e) {
            throw new IOException("Mapped partition capacity overflows", e);
        }
    }

    private static boolean sameCandidate(ExternalVolumeCandidate first, ExternalVolumeCandidate second) {
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
                throw win32Error(api, "GetVolumeInformationW failed for " + volumePath);
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
                throw win32Error(api, "GetDiskFreeSpaceExW failed for " + volumePath);
            }
            capacity = checkedUnsignedLong(total.getValue(), "volume capacity");
            available = checkedUnsignedLong(freeAvailable.getValue(), "available volume capacity");
            IntByReference sectorsPerClusterRef = new IntByReference();
            IntByReference bytesPerSectorRef = new IntByReference();
            IntByReference freeClustersRef = new IntByReference();
            IntByReference totalClustersRef = new IntByReference();
            if (!api.GetDiskFreeSpaceW(volumePath, sectorsPerClusterRef, bytesPerSectorRef,
                    freeClustersRef, totalClustersRef)) {
                throw win32Error(api, "GetDiskFreeSpaceW failed for " + volumePath);
            }
            sectorsPerCluster = sectorsPerClusterRef.getValue();
            bytesPerSector = bytesPerSectorRef.getValue();
            if (sectorsPerCluster <= 0 || bytesPerSector <= 0) {
                throw new IOException("Windows returned invalid filesystem cluster geometry");
            }
            clusterSize = Math.multiplyExact((long) sectorsPerCluster, bytesPerSector);
            long totalFreeBytes = checkedUnsignedLong(totalFree.getValue(), "free volume capacity");
            if (totalFreeBytes > capacity || available > totalFreeBytes) {
                throw new IOException("Windows returned inconsistent total/free/available volume capacities");
            }
            fileSystem = Optional.of(new VolumeFileSystemInfo(fileSystemName, label, volumeSerial,
                    maxComponentLength, capabilities, capacity, totalFreeBytes, available,
                    sectorsPerCluster, bytesPerSector));
        } catch (IOException | ArithmeticException | IllegalArgumentException e) {
            diagnostics.add("Filesystem information unavailable for " + volumePath + ": " + e.getMessage());
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
                    diagnostics.add("Volume extent mapping failed for " + volumePath + ": " + e.getMessage());
                }
            } finally {
                if (!api.CloseHandle(volumeHandle)) {
                    diagnostics.add(win32Error(api, "Could not close volume handle").getMessage());
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
            throw new IOException("GetVolumePathNamesForVolumeNameW failed (Win32 error " + error + ")");
        }
        int chars = required.getValue();
        if (chars <= 0) {
            return List.of();
        }
        if (chars > MAX_VOLUME_PATH_CHARS) {
            throw new IOException("Volume mount-point list exceeds the safety limit");
        }
        char[] buffer = new char[chars];
        if (!api.GetVolumePathNamesForVolumeNameW(volumePath, buffer, buffer.length, required)) {
            throw win32Error(api, "Could not read mount points for " + volumePath);
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
            diagnostics.add("CreateFileW failed for " + purpose + " at " + openPath
                    + " (desiredAccess=0x" + Integer.toHexString(desiredAccess)
                    + ", shareMode=0x" + Integer.toHexString(shareMode)
                    + ", creationDisposition=" + OPEN_EXISTING
                    + ", flagsAndAttributes=0, Win32 error " + api.GetLastError() + ")");
            return null;
        }
        return handle;
    }

    private static VolumeInspection unavailableVolume(String volumePath, RuntimeException error) {
        VolumeInfo volume = new VolumeInfo(volumePath, List.of(), "", "", 0, 0, 0);
        return new VolumeInspection(volume, Optional.empty(), List.of(), Optional.empty(),
                List.of("Volume inspection failed: " + error.getMessage()));
    }

    private static List<DiskExtent> readVolumeExtents(Kernel32 api, Pointer handle) throws IOException {
        if (Native.POINTER_SIZE != 8) {
            throw new IOException("Volume extent structure decoding is enabled only for 64-bit Windows processes");
        }
        byte[] buffer = new byte[VOLUME_EXTENTS_BUFFER_SIZE];
        IntByReference bytesReturned = new IntByReference();
        if (!api.DeviceIoControl(handle, IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS,
                null, 0, buffer, buffer.length, bytesReturned, null)) {
            throw win32Error(api, "IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS failed "
                    + "(volume handle desiredAccess=0, sharing=READ|WRITE|DELETE)");
        }
        if (bytesReturned.getValue() < 4 || bytesReturned.getValue() > buffer.length) {
            throw new IOException("Windows returned an invalid volume extent response length");
        }
        int count = i32(buffer, 0);
        if (count < 0 || count > MAX_EXTENTS) {
            throw new IOException("Volume extent count is invalid or exceeds safety limit");
        }
        int firstExtentOffset = 8;
        int extentSize = 24;
        long required = (long) firstExtentOffset + (long) count * extentSize;
        if (required > bytesReturned.getValue()) {
            throw new IOException("Windows returned an incomplete volume disk extent array");
        }
        List<DiskExtent> extents = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int offset = firstExtentOffset + i * extentSize;
            int diskNumber = i32(buffer, offset);
            long start = i64(buffer, offset + 8);
            long length = i64(buffer, offset + 16);
            if (diskNumber < 0 || start < 0 || length < 0) {
                throw new IOException("Windows returned invalid volume extent values");
            }
            try {
                Math.addExact(start, length);
            } catch (ArithmeticException e) {
                throw new IOException("Volume extent boundary overflows", e);
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
            throw new IOException("Windows returned " + field + " outside the supported signed 64-bit range");
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
            throw new IllegalArgumentException("unsigned value exceeds signed 64-bit range");
        }
        return value;
    }

    private static IOException win32Error(Kernel32 api, String message) {
        return new IOException(message + " (Win32 error " + api.GetLastError() + ")");
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
