package io.github.delokoseni.service.disk;

import java.io.IOException;

public interface DiskReader extends AutoCloseable {
    long getDiskSize();

    int getLogicalSectorSize();

    /**
     * Reads exactly {@code length} bytes or throws; implementations must never write.
     */
    byte[] read(long offset, int length) throws IOException;

    @Override
    void close() throws IOException;
}
