package io.github.delokoseni.service.parser;

import io.github.delokoseni.service.disk.DiskReader;

import java.io.IOException;
import java.util.Arrays;

final class SyntheticDiskReader implements DiskReader {
    private final byte[] bytes;
    private final int sectorSize;
    private final long reportedDiskSize;

    SyntheticDiskReader(byte[] bytes, int sectorSize) {
        this(bytes, sectorSize, bytes.length);
    }

    SyntheticDiskReader(byte[] bytes, int sectorSize, long reportedDiskSize) {
        this.bytes = bytes;
        this.sectorSize = sectorSize;
        this.reportedDiskSize = reportedDiskSize;
    }

    @Override
    public long getDiskSize() {
        return reportedDiskSize;
    }

    @Override
    public int getLogicalSectorSize() {
        return sectorSize;
    }

    @Override
    public byte[] read(long offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > bytes.length - (long) length) {
            throw new IOException("Synthetic read outside disk");
        }
        return Arrays.copyOfRange(bytes, Math.toIntExact(offset), Math.toIntExact(offset + length));
    }

    @Override
    public void close() {
    }
}
