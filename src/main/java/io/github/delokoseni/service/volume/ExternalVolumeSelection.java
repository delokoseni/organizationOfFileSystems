package io.github.delokoseni.service.volume;

import io.github.delokoseni.model.VolumeInspection;

public record ExternalVolumeSelection(VolumeInspection volume, int physicalDiskNumber) {
}
