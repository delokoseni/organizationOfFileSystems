package io.github.delokoseni.service.disk;

import io.github.delokoseni.model.VolumeInspection;
import io.github.delokoseni.service.volume.ExternalVolumeCandidate;

import java.io.IOException;
import java.util.List;

@FunctionalInterface
public interface ExternalDeviceVerifier {
    ExternalVolumeCandidate verify(List<VolumeInspection> volumes) throws IOException;
}
