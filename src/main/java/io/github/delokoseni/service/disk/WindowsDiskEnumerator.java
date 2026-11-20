package io.github.delokoseni.service.disk;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WindowsDiskEnumerator implements DiskEnumerator {
    private static final int DIGCF_PRESENT = 0x00000002;
    private static final int DIGCF_DEVICEINTERFACE = 0x00000010;
    private static final int FILE_SHARE_READ = 0x00000001;
    private static final int FILE_SHARE_WRITE = 0x00000002;
    private static final int OPEN_EXISTING = 3;
    private static final int FILE_DEVICE_DISK = 0x00000007;
    private static final int IOCTL_STORAGE_GET_DEVICE_NUMBER = 0x002D1080;
    private static final int STORAGE_DEVICE_NUMBER_SIZE = 12;
    private static final int ERROR_NO_MORE_ITEMS = 259;
    private static final int ERROR_INSUFFICIENT_BUFFER = 122;
    private static final int SPDRP_DEVICEDESC = 0;
    private static final int SPDRP_FRIENDLYNAME = 12;
    private static final int SPDRP_PHYSICAL_DEVICE_OBJECT_NAME = 0x0D;
    private static final int MAX_PROPERTY_BYTES = 64 * 1024;
    private static final int MAX_DISK_INTERFACES = 4096;
    private static final long INVALID_HANDLE_VALUE = -1L;
    private static final Pattern PHYSICAL_DISK_NAME =
            Pattern.compile("(?i)Harddisk(\\d+)");
    private static final String DISK_INTERFACE_GUID = "{53f56307-b6bf-11d0-94f2-00a0c91efb8b}";

    @Override
    public DiskEnumerationResult enumerate() throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            throw new IOException("Physical disk enumeration is available only on Windows");
        }
        SetupApi api = SetupApiHolder.INSTANCE;
        Memory interfaceGuid = guidMemory();
        Pointer deviceInfoSet = api.SetupDiGetClassDevsW(interfaceGuid, null, null,
                DIGCF_PRESENT | DIGCF_DEVICEINTERFACE);
        if (deviceInfoSet == null || Pointer.nativeValue(deviceInfoSet) == -1L
                || Pointer.nativeValue(deviceInfoSet) == 0xffff_ffffL) {
            throw setupError("SetupDiGetClassDevsW failed");
        }

        List<DiscoveredPhysicalDisk> disks = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        try {
            int index;
            for (index = 0; index < MAX_DISK_INTERFACES; index++) {
                Memory interfaceData = new Memory(interfaceDataSize());
                interfaceData.clear();
                interfaceData.setInt(0, interfaceDataSize());
                if (!api.SetupDiEnumDeviceInterfaces(deviceInfoSet, null, interfaceGuid, index, interfaceData)) {
                    int error = Kernel32Holder.INSTANCE.GetLastError();
                    if (error == ERROR_NO_MORE_ITEMS) {
                        break;
                    }
                    diagnostics.add("SetupDiEnumDeviceInterfaces failed at index " + index
                            + " (Win32 error " + error + "); continuing with the next interface.");
                    continue;
                }

                Memory deviceInfoData = new Memory(deviceInfoDataSize());
                deviceInfoData.clear();
                deviceInfoData.setInt(0, deviceInfoDataSize());
                IntByReference requiredBytes = new IntByReference();
                api.SetupDiGetDeviceInterfaceDetailW(deviceInfoSet, interfaceData,
                        null, 0, requiredBytes, deviceInfoData);
                int detailError = Kernel32Holder.INSTANCE.GetLastError();
                if (detailError != ERROR_INSUFFICIENT_BUFFER
                        || requiredBytes.getValue() < 6
                        || requiredBytes.getValue() > MAX_PROPERTY_BYTES) {
                    diagnostics.add("Could not size disk device-interface detail at index " + index
                            + " (Win32 error " + detailError + "); that interface was skipped.");
                    continue;
                }

                Memory detail = new Memory(requiredBytes.getValue());
                detail.clear();
                detail.setInt(0, Native.POINTER_SIZE == 8 ? 8 : 6);
                deviceInfoData.clear();
                deviceInfoData.setInt(0, deviceInfoDataSize());
                if (!api.SetupDiGetDeviceInterfaceDetailW(deviceInfoSet, interfaceData,
                        detail, requiredBytes.getValue(), requiredBytes, deviceInfoData)) {
                    diagnostics.add("Could not read disk device-interface detail at index " + index
                            + " (Win32 error " + Kernel32Holder.INSTANCE.GetLastError()
                            + "); that interface was skipped.");
                    continue;
                }
                String devicePath = detail.getWideString(4);
                String pdoName = readProperty(api, deviceInfoSet, deviceInfoData,
                        SPDRP_PHYSICAL_DEVICE_OBJECT_NAME);
                int diskNumber = parseDiskNumber(pdoName);
                if (diskNumber < 0) {
                    diskNumber = readDiskNumber(devicePath, index, diagnostics);
                }
                if (diskNumber < 0) {
                    diagnostics.add("Disk interface at index " + index
                            + " had no recognizable physical disk number in its PnP object name "
                            + "or storage device-number response.");
                    continue;
                }
                String model = readProperty(api, deviceInfoSet, deviceInfoData, SPDRP_FRIENDLYNAME);
                if (model.isBlank()) {
                    model = readProperty(api, deviceInfoSet, deviceInfoData, SPDRP_DEVICEDESC);
                }
                if (model.isBlank()) {
                    diagnostics.add("Model information was unavailable for physical disk " + diskNumber + ".");
                }
                disks.add(new DiscoveredPhysicalDisk(diskNumber,
                        "\\\\.\\PhysicalDrive" + diskNumber, model));
            }
            if (index == MAX_DISK_INTERFACES) {
                diagnostics.add("Physical disk interface enumeration reached the configured safety limit.");
            }
        } finally {
            if (!api.SetupDiDestroyDeviceInfoList(deviceInfoSet)) {
                diagnostics.add("SetupDiDestroyDeviceInfoList failed (Win32 error "
                        + Kernel32Holder.INSTANCE.GetLastError() + ")");
            }
        }
        List<DiscoveredPhysicalDisk> uniqueDisks = disks.stream()
                .collect(java.util.stream.Collectors.toMap(
                        DiscoveredPhysicalDisk::diskNumber, disk -> disk,
                        (first, ignored) -> first, java.util.LinkedHashMap::new))
                .values().stream().toList();
        return new DiskEnumerationResult(uniqueDisks, diagnostics);
    }

    static int parseDiskNumber(String physicalDeviceObjectName) {
        if (physicalDeviceObjectName == null) {
            return -1;
        }
        Matcher matcher = PHYSICAL_DISK_NAME.matcher(physicalDeviceObjectName);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String readProperty(
            SetupApi api, Pointer deviceInfoSet, Pointer deviceInfoData, int property) {
        Memory buffer = new Memory(MAX_PROPERTY_BYTES);
        IntByReference required = new IntByReference();
        IntByReference propertyType = new IntByReference();
        if (!api.SetupDiGetDeviceRegistryPropertyW(deviceInfoSet, deviceInfoData, property,
                propertyType, buffer, (int) buffer.size(), required)) {
            return "";
        }
        if (required.getValue() < 2 || required.getValue() > buffer.size()) {
            return "";
        }
        return buffer.getWideString(0).trim();
    }

    private static int readDiskNumber(String devicePath, int interfaceIndex, List<String> diagnostics) {
        if (devicePath == null || devicePath.isBlank()) {
            diagnostics.add("Disk interface at index " + interfaceIndex + " returned an empty device path.");
            return -1;
        }
        Kernel32 api = Kernel32Holder.INSTANCE;
        int shareMode = FILE_SHARE_READ | FILE_SHARE_WRITE;
        Pointer handle = api.CreateFileW(devicePath, 0, shareMode, null, OPEN_EXISTING, 0, null);
        if (isInvalidHandle(handle)) {
            diagnostics.add("Could not query the disk number for interface " + interfaceIndex
                    + " with zero desired access (Win32 error " + api.GetLastError() + ").");
            return -1;
        }
        try {
            byte[] response = new byte[STORAGE_DEVICE_NUMBER_SIZE];
            IntByReference bytesReturned = new IntByReference();
            if (!api.DeviceIoControl(handle, IOCTL_STORAGE_GET_DEVICE_NUMBER, null, 0,
                    response, response.length, bytesReturned, null)) {
                diagnostics.add("IOCTL_STORAGE_GET_DEVICE_NUMBER failed for interface " + interfaceIndex
                        + " (Win32 error " + api.GetLastError() + ").");
                return -1;
            }
            if (bytesReturned.getValue() < STORAGE_DEVICE_NUMBER_SIZE) {
                diagnostics.add("IOCTL_STORAGE_GET_DEVICE_NUMBER returned an incomplete response for "
                        + "interface " + interfaceIndex + ".");
                return -1;
            }
            int deviceType = readInt32LE(response, 0);
            int diskNumber = readInt32LE(response, 4);
            if (deviceType != FILE_DEVICE_DISK || diskNumber < 0) {
                diagnostics.add("IOCTL_STORAGE_GET_DEVICE_NUMBER returned an invalid disk identity for "
                        + "interface " + interfaceIndex + ".");
                return -1;
            }
            return diskNumber;
        } finally {
            if (!api.CloseHandle(handle)) {
                diagnostics.add("Could not close the zero-access handle for disk interface "
                        + interfaceIndex + " (Win32 error " + api.GetLastError() + ").");
            }
        }
    }

    private static boolean isInvalidHandle(Pointer handle) {
        if (handle == null) {
            return true;
        }
        long value = Pointer.nativeValue(handle);
        return value == INVALID_HANDLE_VALUE || value == 0xffff_ffffL || value == 0;
    }

    private static int readInt32LE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static Memory guidMemory() {
        Memory guid = new Memory(16);
        guid.clear();
        String[] parts = DISK_INTERFACE_GUID.replace("{", "").replace("}", "").split("-");
        guid.setInt(0, (int) Long.parseLong(parts[0], 16));
        guid.setShort(4, (short) Integer.parseInt(parts[1], 16));
        guid.setShort(6, (short) Integer.parseInt(parts[2], 16));
        String tail = parts[3] + parts[4];
        for (int i = 0; i < 8; i++) {
            guid.setByte(8L + i, (byte) Integer.parseInt(tail.substring(i * 2, i * 2 + 2), 16));
        }
        return guid;
    }

    private static int interfaceDataSize() {
        return Native.POINTER_SIZE == 8 ? 32 : 28;
    }

    private static int deviceInfoDataSize() {
        return Native.POINTER_SIZE == 8 ? 32 : 28;
    }

    private static IOException setupError(String message) {
        return new IOException(message + " (Win32 error "
                + Kernel32Holder.INSTANCE.GetLastError() + ")");
    }

    private interface SetupApi extends StdCallLibrary {
        Pointer SetupDiGetClassDevsW(Pointer classGuid, String enumerator,
                                     Pointer parentWindow, int flags);

        boolean SetupDiEnumDeviceInterfaces(Pointer deviceInfoSet, Pointer deviceInfoData,
                                            Pointer interfaceClassGuid, int memberIndex,
                                            Pointer deviceInterfaceData);

        boolean SetupDiGetDeviceInterfaceDetailW(Pointer deviceInfoSet, Pointer deviceInterfaceData,
                                                 Pointer deviceInterfaceDetailData, int detailDataSize,
                                                 IntByReference requiredSize, Pointer deviceInfoData);

        boolean SetupDiGetDeviceRegistryPropertyW(Pointer deviceInfoSet, Pointer deviceInfoData,
                                                 int property, IntByReference propertyRegDataType,
                                                 Pointer propertyBuffer, int propertyBufferSize,
                                                 IntByReference requiredSize);

        boolean SetupDiDestroyDeviceInfoList(Pointer deviceInfoSet);

    }

    private static final class SetupApiHolder {
        private static final SetupApi INSTANCE =
                Native.load("setupapi", SetupApi.class, W32APIOptions.UNICODE_OPTIONS);
    }

    private interface Kernel32 extends StdCallLibrary {
        int GetLastError();

        Pointer CreateFileW(String fileName, int desiredAccess, int shareMode, Pointer securityAttributes,
                            int creationDisposition, int flagsAndAttributes, Pointer templateFile);

        boolean DeviceIoControl(Pointer device, int controlCode, byte[] input, int inputSize,
                                byte[] output, int outputSize, IntByReference bytesReturned, Pointer overlapped);

        boolean CloseHandle(Pointer object);
    }

    private static final class Kernel32Holder {
        private static final Kernel32 INSTANCE =
                Native.load("kernel32", Kernel32.class, W32APIOptions.UNICODE_OPTIONS);
    }
}
