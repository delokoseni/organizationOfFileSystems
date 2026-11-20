package io.github.delokoseni.service.disk;

import java.io.IOException;
import java.util.List;

public interface DiskEnumerator {
    DiskEnumerationResult enumerate() throws IOException;
}
