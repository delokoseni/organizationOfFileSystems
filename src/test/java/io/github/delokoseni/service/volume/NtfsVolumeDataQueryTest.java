package io.github.delokoseni.service.volume;

import com.sun.jna.ptr.IntByReference;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NtfsVolumeDataQueryTest {
    @Test
    void queriesReadOnlyControlAndParsesReturnedByteCount() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        var data = NtfsVolumeDataQuery.read((buffer, bytesReturned) -> {
            callCount.incrementAndGet();
            assertEquals(NtfsVolumeDataQuery.OUTPUT_BUFFER_SIZE, buffer.length);
            writeValidStructure(buffer);
            bytesReturned.setValue(128);
            return true;
        }, () -> {
            throw new AssertionError("GetLastError must not be read after success");
        });

        assertEquals(1, callCount.get());
        assertEquals(500_084_735, data.numberOfSectors());
        assertEquals(Long.MIN_VALUE + 123, data.volumeSerialNumber());
    }

    @Test
    void capturesAndReportsLastErrorImmediatelyWhenControlFails() {
        AtomicInteger sequence = new AtomicInteger();
        IOException error = assertThrows(IOException.class, () -> NtfsVolumeDataQuery.read(
                (buffer, bytesReturned) -> {
                    sequence.set(1);
                    return false;
                },
                () -> {
                    assertEquals(1, sequence.get(), "GetLastError must immediately follow failure");
                    sequence.set(2);
                    return 50;
                }));

        assertEquals(2, sequence.get());
        assertTrue(error.getMessage().contains("Win32 error 50 ERROR_NOT_SUPPORTED"));
    }

    private static void writeValidStructure(byte[] bytes) {
        System.arraycopy(NtfsVolumeDataBufferParserTest.validBuffer(), 0, bytes, 0,
                NtfsVolumeDataBufferParserTest.validBuffer().length);
    }
}
