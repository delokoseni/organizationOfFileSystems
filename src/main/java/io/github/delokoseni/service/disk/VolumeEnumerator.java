package io.github.delokoseni.service.disk;

import io.github.delokoseni.model.VolumeInspection;

import java.io.IOException;
import java.util.List;

@FunctionalInterface
public interface VolumeEnumerator {
    List<VolumeInspection> enumerate() throws IOException;
}
