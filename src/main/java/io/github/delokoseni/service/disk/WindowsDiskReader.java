package io.github.delokoseni.service.disk;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.io.EOFException;
import java.io.IOException;
import java.util.Locale;

public final class WindowsDiskReader implements DiskReader {
    private static final int STORAGE_DEVICE_PROPERTY = 0;
    private static final int STORAGE_ACCESS_ALIGNMENT_PROPERTY = 6;
    private static final int STORAGE_PROPERTY_QUERY_SIZE = 12;
    private static final int PROPERTY_STANDARD_QUERY = 0;
    private static final int GENERIC_READ = 0x80000000;
    private static final int FILE_SHARE_READ = 0x00000001;
    private static final int FILE_SHARE_WRITE = 0x00000002;
    private static final int OPEN_EXISTING = 3;
    private static final int FILE_BEGIN = 0;
    private static final int INVALID_HANDLE_VALUE = -1;
    private static final int IOCTL_DISK_GET_DRIVE_GEOMETRY_EX = 0x000700A0;
    private static final int IOCTL_DISK_GET_DRIVE_LAYOUT_EX = 0x00070050;
    private static final int IOCTL_STORAGE_QUERY_PROPERTY = 0x002D1400;
    private static final int GEOMETRY_EX_BUFFER_SIZE = 64;
    private static final int DRIVE_LAYOUT_BUFFER_SIZE = 4 * 1024 * 1024;
    private static final int STORAGE_DESCRIPTOR_BUFFER_SIZE = 4096;
    private static final int MAX_READ_BYTES = 64 * 1024 * 1024;
    private static final int MAX_LOGICAL_SECTOR_SIZE = 1024 * 1024;

    private final Kernel32 api;
    private final Pointer handle;
    private final String devicePath;
    private final long diskSize;
    private final int logicalSectorSize;
    private boolean closed;

    private WindowsDiskReader(
            Kernel32 api, Pointer handle, String devicePath, long diskSize, int logicalSectorSize) {
        this.api = api;
        this.handle = handle;
        this.devicePath = devicePath;
        this.diskSize = diskSize;
        this.logicalSectorSize = logicalSectorSize;
    }

    public static WindowsDiskReader open(int diskNumber) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            throw new IOException("Physical disk inspection is available only on Windows");
        }
        return open(diskNumber, Kernel32Holder.INSTANCE);
    }

    static WindowsDiskReader open(int diskNumber, Kernel32 api) throws IOException {
        if (diskNumber < 0) {
            throw new IllegalArgumentException("Disk number cannot be negative");
        }
        String path = "\\\\.\\PhysicalDrive" + diskNumber;
        Pointer handle = api.CreateFileW(path, GENERIC_READ,
                FILE_SHARE_READ | FILE_SHARE_WRITE, null, OPEN_EXISTING, 0, null);
        long nativeHandle = handle == null ? INVALID_HANDLE_VALUE : Pointer.nativeValue(handle);
        if (handle == null || nativeHandle == INVALID_HANDLE_VALUE || nativeHandle == 0xffff_ffffL) {
            throw new DiskOpenException(path, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
                    OPEN_EXISTING, 0, api.GetLastError());
        }

        try {
            byte[] geometry = new byte[GEOMETRY_EX_BUFFER_SIZE];
            IntByReference bytesReturned = new IntByReference();
            if (!api.DeviceIoControl(handle, IOCTL_DISK_GET_DRIVE_GEOMETRY_EX, null, 0,
                    geometry, geometry.length, bytesReturned, null)) {
                int error = api.GetLastError();
                throw nativeError("IOCTL_DISK_GET_DRIVE_GEOMETRY_EX", path, error);
            }
            if (bytesReturned.getValue() < 32) {
                throw new IOException("IOCTL_DISK_GET_DRIVE_GEOMETRY_EX returned an incomplete response for "
                        + path + " (" + bytesReturned.getValue() + " bytes)");
            }
            int sectorSize = readInt32LE(geometry, 20);
            long size;
            try {
                size = readInt64LE(geometry, 24);
            } catch (IllegalArgumentException e) {
                throw new IOException("Windows returned unsupported disk capacity", e);
            }
            if (sectorSize < 512 || sectorSize > MAX_LOGICAL_SECTOR_SIZE
                    || size < sectorSize || size % sectorSize != 0) {
                throw new IOException("Windows returned invalid disk geometry");
            }
            return new WindowsDiskReader(api, handle, path, size, sectorSize);
        } catch (IOException | RuntimeException e) {
            api.CloseHandle(handle);
            throw e;
        }
    }

    @Override
    public long getDiskSize() {
        return diskSize;
    }

    @Override
    public int getLogicalSectorSize() {
        return logicalSectorSize;
    }

    public synchronized WindowsPartitionLayout readWindowsPartitionLayout() throws IOException {
        if (closed) {
            throw new IOException("Disk reader is closed");
        }
        byte[] layout = new byte[DRIVE_LAYOUT_BUFFER_SIZE];
        IntByReference bytesReturned = new IntByReference();
        if (!api.DeviceIoControl(handle, IOCTL_DISK_GET_DRIVE_LAYOUT_EX, null, 0,
                layout, layout.length, bytesReturned, null)) {
            int error = api.GetLastError();
            throw nativeError("IOCTL_DISK_GET_DRIVE_LAYOUT_EX failed", devicePath, error);
        }
        return new WindowsLayoutBufferParser().parse(layout, bytesReturned.getValue());
    }

    public synchronized StorageDeviceIdentity readStorageDeviceIdentity() throws IOException {
        if (closed) {
            throw new IOException("Disk reader is closed");
        }
        byte[] descriptor = readStorageDescriptor(STORAGE_DEVICE_PROPERTY, STORAGE_DESCRIPTOR_BUFFER_SIZE);
        if (descriptor.length < 36) {
            throw new IOException("Windows returned an incomplete storage device descriptor");
        }
        long descriptorSize = Integer.toUnsignedLong(readInt32LE(descriptor, 4));
        if (descriptorSize < 36 || descriptorSize > descriptor.length) {
            throw new IOException("Storage device descriptor size is outside returned buffer bounds");
        }
        int descriptorLength = Math.toIntExact(descriptorSize);
        String vendor = descriptorString(descriptor, descriptorLength,
                readInt32LE(descriptor, 12));
        String product = descriptorString(descriptor, descriptorLength,
                readInt32LE(descriptor, 16));
        String serial = descriptorString(descriptor, descriptorLength,
                readInt32LE(descriptor, 24));
        boolean removableMedia = descriptor[10] != 0;
        return new StorageDeviceIdentity(vendor, product, serial, removableMedia);
    }

    public synchronized int readPhysicalSectorSize() throws IOException {
        if (closed) {
            throw new IOException("Disk reader is closed");
        }
        byte[] descriptor = readStorageDescriptor(STORAGE_ACCESS_ALIGNMENT_PROPERTY, 32);
        if (descriptor.length < 24) {
            throw new IOException("Windows returned an incomplete storage alignment descriptor");
        }
        long size = Integer.toUnsignedLong(readInt32LE(descriptor, 4));
        long physicalSectorSize = Integer.toUnsignedLong(readInt32LE(descriptor, 20));
        if (size < 24 || size > descriptor.length
                || physicalSectorSize <= 0 || physicalSectorSize > Integer.MAX_VALUE) {
            throw new IOException("Windows returned invalid physical sector information");
        }
        return (int) physicalSectorSize;
    }

    private byte[] readStorageDescriptor(int propertyId, int bufferSize) throws IOException {
        if (propertyId != STORAGE_DEVICE_PROPERTY && propertyId != STORAGE_ACCESS_ALIGNMENT_PROPERTY) {
            throw new IllegalArgumentException("Storage property is not on the read-only allowlist");
        }
        // The trailing AdditionalParameters[1] and DWORD alignment make this structure 12 bytes.
        byte[] query = new byte[STORAGE_PROPERTY_QUERY_SIZE];
        writeInt32LE(query, 0, propertyId);
        writeInt32LE(query, 4, PROPERTY_STANDARD_QUERY);
        if (bufferSize <= 0 || bufferSize > STORAGE_DESCRIPTOR_BUFFER_SIZE) {
            throw new IllegalArgumentException("Storage descriptor buffer exceeds configured limit");
        }
        byte[] descriptor = new byte[bufferSize];
        IntByReference bytesReturned = new IntByReference();
        if (!api.DeviceIoControl(handle, IOCTL_STORAGE_QUERY_PROPERTY, query, query.length,
                descriptor, descriptor.length, bytesReturned, null)) {
            int error = api.GetLastError();
            throw nativeError("IOCTL_STORAGE_QUERY_PROPERTY failed for property " + propertyId,
                    devicePath, error);
        }
        if (bytesReturned.getValue() < 4 || bytesReturned.getValue() > descriptor.length) {
            throw new IOException("Windows returned an incomplete storage property descriptor");
        }
        return java.util.Arrays.copyOf(descriptor, bytesReturned.getValue());
    }

    @Override
    public synchronized byte[] read(long offset, int length) throws IOException {
        if (closed) {
            throw new IOException("Disk reader is closed");
        }
        DiskReadRange.validate(offset, length, diskSize);
        if (length > MAX_READ_BYTES) {
            throw new IllegalArgumentException("Read request exceeds the 64 MiB safety limit");
        }
        long requestedEnd = Math.addExact(offset, (long) length);
        long alignedOffset = offset - offset % logicalSectorSize;
        long endingSector = requestedEnd / logicalSectorSize
                + (requestedEnd % logicalSectorSize == 0 ? 0 : 1);
        long alignedEnd;
        try {
            alignedEnd = Math.multiplyExact(endingSector, (long) logicalSectorSize);
        } catch (ArithmeticException e) {
            throw new IOException("Aligned disk read range overflows", e);
        }
        if (alignedEnd > diskSize) {
            throw new IOException(readContext("Aligned read range exceeds disk capacity",
                    alignedOffset, Math.toIntExact(Math.min(alignedEnd - alignedOffset, Integer.MAX_VALUE)), 0)
                    + " for " + devicePath);
        }
        long alignedLengthLong = alignedEnd - alignedOffset;
        if (alignedLengthLong <= 0 || alignedLengthLong > MAX_READ_BYTES) {
            throw new IllegalArgumentException("Sector-aligned read request exceeds the 64 MiB safety limit");
        }
        int alignedLength = Math.toIntExact(alignedLengthLong);
        byte[] alignedData = new byte[alignedLength];
        LongByReference newPosition = new LongByReference();
        if (!api.SetFilePointerEx(handle, alignedOffset, newPosition, FILE_BEGIN)) {
            int error = api.GetLastError();
            throw nativeError(readContext("SetFilePointerEx failed", alignedOffset, alignedLength, 0)
                            + ", requestedOffset=" + offset + ", requestedLength=" + length,
                    devicePath, error);
        }
        if (newPosition.getValue() != alignedOffset) {
            throw new IOException(readContext("SetFilePointerEx positioned at "
                    + newPosition.getValue() + " instead of the aligned read offset",
                    alignedOffset, alignedLength, 0)
                    + ", requestedOffset=" + offset + ", requestedLength=" + length
                    + " for " + devicePath);
        }
        int totalRead = 0;
        while (totalRead < alignedLength) {
            int remaining = alignedLength - totalRead;
            byte[] chunk = new byte[remaining];
            IntByReference bytesRead = new IntByReference();
            if (!api.ReadFile(handle, chunk, chunk.length, bytesRead, null)) {
                int error = api.GetLastError();
                int actualBytes = bytesRead.getValue();
                String context = readContext("ReadFile failed", alignedOffset + totalRead,
                        chunk.length, actualBytes) + ", requestedOffset=" + offset
                        + ", requestedLength=" + length;
                throw nativeError(context, devicePath, error);
            }
            int count = bytesRead.getValue();
            if (count <= 0 || count > chunk.length) {
                throw new EOFException(readContext("Incomplete aligned read", alignedOffset + totalRead,
                        chunk.length, totalRead + Math.max(count, 0)) + ", requestedOffset=" + offset
                        + ", requestedLength=" + length + " for " + devicePath);
            }
            if (count % logicalSectorSize != 0) {
                throw new EOFException(readContext("Device returned a partial non-sector-aligned read",
                        alignedOffset + totalRead, chunk.length, totalRead + count)
                        + ", requestedOffset=" + offset + ", requestedLength=" + length
                        + " for " + devicePath);
            }
            System.arraycopy(chunk, 0, alignedData, totalRead, count);
            totalRead += count;
        }
        int sliceOffset = Math.toIntExact(offset - alignedOffset);
        return java.util.Arrays.copyOfRange(alignedData, sliceOffset, sliceOffset + length);
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            if (!api.CloseHandle(handle)) {
                int error = api.GetLastError();
                throw nativeError("Could not close physical disk handle", devicePath, error);
            }
        }
    }

    private String readContext(String operation, long offset, int length, int bytesRead) {
        return operation + " (offset=" + offset + ", length=" + length
                + ", diskCapacity=" + diskSize + ", logicalSectorSize=" + logicalSectorSize
                + ", bytesRead=" + bytesRead + ")";
    }

    private static DiskNativeException nativeError(String message, String path, int error) {
        return new DiskNativeException(message, path, error);
    }

    private static int readInt32LE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static void writeInt32LE(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private static String descriptorString(byte[] descriptor, int length, int offset) {
        if (offset < 36 || offset >= length) {
            return "";
        }
        int end = offset;
        while (end < length && descriptor[end] != 0) {
            int value = descriptor[end] & 0xff;
            if (value < 0x20 || value > 0x7e) {
                return "";
            }
            end++;
        }
        if (end == length || end == offset) {
            return "";
        }
        return new String(descriptor, offset, end - offset,
                java.nio.charset.StandardCharsets.US_ASCII).trim();
    }

    private static long readInt64LE(byte[] bytes, int offset) {
        long low = Integer.toUnsignedLong(readInt32LE(bytes, offset));
        long high = Integer.toUnsignedLong(readInt32LE(bytes, offset + 4));
        long value = (high << 32) | low;
        if (value < 0) {
            throw new IllegalArgumentException("Disk sizes above signed 64-bit range are unsupported");
        }
        return value;
    }

    interface Kernel32 extends StdCallLibrary {
        Pointer CreateFileW(String fileName, int desiredAccess, int shareMode, Pointer securityAttributes,
                            int creationDisposition, int flagsAndAttributes, Pointer templateFile);

        boolean DeviceIoControl(Pointer device, int controlCode, byte[] input, int inputSize,
                                byte[] output, int outputSize, IntByReference bytesReturned, Pointer overlapped);

        boolean SetFilePointerEx(Pointer file, long distanceToMove, LongByReference newFilePointer, int moveMethod);

        boolean ReadFile(Pointer file, byte[] buffer, int bytesToRead, IntByReference bytesRead, Pointer overlapped);

        boolean CloseHandle(Pointer object);

        int GetLastError();
    }

    private static final class Kernel32Holder {
        private static final Kernel32 INSTANCE =
                Native.load("kernel32", Kernel32.class, W32APIOptions.UNICODE_OPTIONS);
    }
}
