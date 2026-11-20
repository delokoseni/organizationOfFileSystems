package io.github.delokoseni.service.volume;

import com.sun.jna.ptr.IntByReference;
import io.github.delokoseni.model.NtfsVolumeData;
import io.github.delokoseni.service.disk.Win32ErrorNames;

import java.io.IOException;
import java.util.function.IntSupplier;

final class NtfsVolumeDataQuery {
    static final int FSCTL_GET_NTFS_VOLUME_DATA = 0x00090064;
    static final int OUTPUT_BUFFER_SIZE = 128;

    @FunctionalInterface
    interface DeviceIoControlCall {
        boolean invoke(byte[] output, IntByReference bytesReturned);
    }

    private NtfsVolumeDataQuery() {
    }

    static NtfsVolumeData read(DeviceIoControlCall deviceIoControl, IntSupplier getLastError)
            throws IOException {
        byte[] buffer = new byte[OUTPUT_BUFFER_SIZE];
        IntByReference bytesReturned = new IntByReference();
        if (!deviceIoControl.invoke(buffer, bytesReturned)) {
            int error = getLastError.getAsInt();
            throw new IOException("FSCTL_GET_NTFS_VOLUME_DATA failed "
                    + "(Win32 error " + error + " " + Win32ErrorNames.name(error) + ")");
        }
        return NtfsVolumeDataBufferParser.parse(buffer, bytesReturned.getValue());
    }
}
