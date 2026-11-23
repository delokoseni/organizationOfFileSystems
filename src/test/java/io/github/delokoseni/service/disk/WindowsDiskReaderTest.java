package io.github.delokoseni.service.disk;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsDiskReaderTest {
    private static final int DISK_SIZE = 64 * 1024;
    private static final int SECTOR_SIZE = 512;

    @Test
    void opensReadOnlyAndUsesStoragePropertyQueryLayoutAndSeparatePropertyIds() throws Exception {
        MockKernel32 api = new MockKernel32();
        try (WindowsDiskReader reader = WindowsDiskReader.open(1, api)) {
            assertEquals(DISK_SIZE, reader.getDiskSize());
            assertEquals(SECTOR_SIZE, reader.getLogicalSectorSize());
            assertEquals("ADATA SE760 SCSI Disk Device",
                    reader.readStorageDeviceIdentity().modelName());
            assertEquals(4096, reader.readPhysicalSectorSize());

            assertEquals(2, api.queries.size());
            assertEquals(12, api.queries.get(0).length);
            assertEquals(0, readInt32LE(api.queries.get(0), 0));
            assertEquals(0, readInt32LE(api.queries.get(0), 4));
            assertEquals(6, readInt32LE(api.queries.get(1), 0));
            assertEquals(0, readInt32LE(api.queries.get(1), 4));
            assertEquals(0x80000000, api.desiredAccess);
            assertTrue(api.readOnlyHandle);
        }
    }

    @Test
    void reportsDeviceIoControlErrorAndAllowsOtherReadOnlyAnalysis() throws Exception {
        MockKernel32 api = new MockKernel32();
        api.failQueryProperty = 0;
        api.lastError = 87;
        try (WindowsDiskReader reader = WindowsDiskReader.open(1, api)) {
            DiskNativeException error = assertThrows(
                    DiskNativeException.class, reader::readStorageDeviceIdentity);
            assertEquals(87, error.win32Error());
            assertEquals("ERROR_INVALID_PARAMETER", error.win32ErrorName());
            assertTrue(error.getMessage().contains("свойство 0"));
            assertTrue(error.getMessage().contains("ошибка Win32 87 ERROR_INVALID_PARAMETER"));
            assertArrayEquals(new byte[]{7, 8, 9}, reader.read(7, 3));
            assertEquals(4096, reader.readPhysicalSectorSize());
        }
    }

    @Test
    void rejectsMalformedStorageDescriptorWithoutFailingRawReads() throws Exception {
        MockKernel32 api = new MockKernel32();
        api.malformedDeviceDescriptor = true;
        try (WindowsDiskReader reader = WindowsDiskReader.open(1, api)) {
            assertTrue(assertThrows(IOException.class, reader::readStorageDeviceIdentity)
                    .getMessage().contains("неполный дескриптор устройства хранения"));
            assertArrayEquals(new byte[]{7, 8, 9}, reader.read(7, 3));
        }
    }

    @Test
    void readsUnalignedAndPartialRangesAndRejectsInvalidRanges() throws Exception {
        MockKernel32 api = new MockKernel32();
        api.maxReadBytes = SECTOR_SIZE;
        try (WindowsDiskReader reader = WindowsDiskReader.open(1, api)) {
            byte[] expected = new byte[1023];
            for (int i = 0; i < expected.length; i++) {
                expected[i] = (byte) (i + 1);
            }
            assertArrayEquals(expected, reader.read(1, expected.length));
            assertEquals(List.of(1024, 512), api.nativeReadLengths);
            assertEquals(List.of(0L), api.seekOffsets);
            assertThrows(IllegalArgumentException.class, () -> reader.read(-1, 1));
            assertThrows(IllegalArgumentException.class, () -> reader.read(0, 0));
            assertThrows(IllegalArgumentException.class, () -> reader.read(DISK_SIZE - 1, 2));
            assertThrows(IllegalArgumentException.class, () -> reader.read(Long.MAX_VALUE, 1));
        }
    }

    @Test
    void reportsReadFileFailureWithExactRangeAndWin32Error() throws Exception {
        MockKernel32 api = new MockKernel32();
        api.failRead = true;
        api.lastError = 1117;
        try (WindowsDiskReader reader = WindowsDiskReader.open(1, api)) {
            DiskNativeException error = assertThrows(DiskNativeException.class,
                    () -> reader.read(1024, 512));
            assertEquals(1117, error.win32Error());
            assertEquals("ERROR_IO_DEVICE", error.win32ErrorName());
            assertTrue(error.getMessage().contains("\\\\.\\PhysicalDrive1"));
            assertTrue(error.getMessage().contains("offset=1024"));
            assertTrue(error.getMessage().contains("length=512"));
            assertTrue(error.getMessage().contains("diskCapacity=65536"));
            assertTrue(error.getMessage().contains("logicalSectorSize=512"));
            assertTrue(error.getMessage().contains("bytesRead=0"));
        }
    }

    @Test
    void serializesConcurrentSeekAndReadOperations() throws Exception {
        MockKernel32 api = new MockKernel32();
        try (WindowsDiskReader reader = WindowsDiskReader.open(1, api);
             var executor = Executors.newFixedThreadPool(4)) {
            List<java.util.concurrent.Future<byte[]>> reads = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int offset = i * 31;
                reads.add(executor.submit(() -> reader.read(offset, 7)));
            }
            for (int i = 0; i < reads.size(); i++) {
                byte[] expected = new byte[7];
                for (int j = 0; j < expected.length; j++) {
                    expected[j] = (byte) (i * 31 + j);
                }
                assertArrayEquals(expected, reads.get(i).get(5, TimeUnit.SECONDS));
            }
        }
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

    private static final class MockKernel32 implements WindowsDiskReader.Kernel32 {
        private final Pointer handle = new Pointer(1);
        private final byte[] disk = new byte[DISK_SIZE];
        private final List<byte[]> queries = new ArrayList<>();
        private final List<Integer> nativeReadLengths = new ArrayList<>();
        private final List<Long> seekOffsets = new ArrayList<>();
        private long filePosition;
        private int desiredAccess;
        private int lastError;
        private int maxReadBytes = Integer.MAX_VALUE;
        private int failQueryProperty = -1;
        private boolean failRead;
        private boolean malformedDeviceDescriptor;
        private boolean readOnlyHandle;

        private MockKernel32() {
            for (int i = 0; i < disk.length; i++) {
                disk[i] = (byte) i;
            }
        }

        @Override
        public Pointer CreateFileW(String fileName, int access, int shareMode, Pointer securityAttributes,
                                   int creationDisposition, int flagsAndAttributes, Pointer templateFile) {
            assertEquals("\\\\.\\PhysicalDrive1", fileName);
            desiredAccess = access;
            readOnlyHandle = access == 0x80000000 && flagsAndAttributes == 0;
            return handle;
        }

        @Override
        public boolean DeviceIoControl(Pointer device, int controlCode, byte[] input, int inputSize,
                                       byte[] output, int outputSize,
                                       IntByReference bytesReturned, Pointer overlapped) {
            if (controlCode == 0x000700A0) {
                writeInt32LE(output, 20, SECTOR_SIZE);
                writeInt64LE(output, 24, DISK_SIZE);
                bytesReturned.setValue(32);
                return true;
            }
            if (controlCode != 0x002D1400 || input == null || inputSize != input.length) {
                lastError = 87;
                return false;
            }
            queries.add(input.clone());
            int property = readInt32LE(input, 0);
            if (property == failQueryProperty) {
                return false;
            }
            if (property == 0) {
                byte[] descriptor = deviceDescriptor();
                System.arraycopy(descriptor, 0, output, 0, descriptor.length);
                bytesReturned.setValue(malformedDeviceDescriptor ? 16 : descriptor.length);
                return true;
            }
            if (property == 6) {
                writeInt32LE(output, 0, 28);
                writeInt32LE(output, 4, 28);
                writeInt32LE(output, 16, SECTOR_SIZE);
                writeInt32LE(output, 20, 4096);
                bytesReturned.setValue(28);
                return true;
            }
            lastError = 87;
            return false;
        }

        @Override
        public boolean SetFilePointerEx(Pointer file, long distanceToMove,
                                        LongByReference newFilePointer, int moveMethod) {
            seekOffsets.add(distanceToMove);
            filePosition = distanceToMove;
            newFilePointer.setValue(filePosition);
            return true;
        }

        @Override
        public boolean ReadFile(Pointer file, byte[] buffer, int bytesToRead,
                                IntByReference bytesRead, Pointer overlapped) {
            if (failRead) {
                return false;
            }
            if (filePosition % SECTOR_SIZE != 0 || bytesToRead % SECTOR_SIZE != 0) {
                lastError = 87;
                return false;
            }
            nativeReadLengths.add(bytesToRead);
            int count = Math.min(bytesToRead, maxReadBytes);
            System.arraycopy(disk, (int) filePosition, buffer, 0, count);
            filePosition += count;
            bytesRead.setValue(count);
            return true;
        }

        @Override
        public boolean CloseHandle(Pointer object) {
            return true;
        }

        @Override
        public int GetLastError() {
            return lastError;
        }

        private static byte[] deviceDescriptor() {
            byte[] descriptor = new byte[80];
            writeInt32LE(descriptor, 0, descriptor.length);
            writeInt32LE(descriptor, 4, descriptor.length);
            descriptor[10] = 1;
            writeInt32LE(descriptor, 12, 36);
            writeInt32LE(descriptor, 16, 42);
            writeInt32LE(descriptor, 24, 0);
            byte[] vendor = "ADATA".getBytes(StandardCharsets.US_ASCII);
            byte[] product = "SE760 SCSI Disk Device".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(vendor, 0, descriptor, 36, vendor.length);
            descriptor[36 + vendor.length] = 0;
            System.arraycopy(product, 0, descriptor, 42, product.length);
            descriptor[42 + product.length] = 0;
            return descriptor;
        }

        private static void writeInt64LE(byte[] bytes, int offset, long value) {
            writeInt32LE(bytes, offset, (int) value);
            writeInt32LE(bytes, offset + 4, (int) (value >>> 32));
        }
    }
}
