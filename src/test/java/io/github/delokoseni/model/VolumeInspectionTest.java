package io.github.delokoseni.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolumeInspectionTest {
    @Test
    void representsMissingFilesystemInformationWithoutInventingValues() {
        VolumeInfo volume = new VolumeInfo("\\\\?\\Volume{x}\\", List.of(), "", "",
                0, 0, 0);
        VolumeInspection inspection = new VolumeInspection(volume, Optional.empty(),
                List.of(), Optional.empty(), List.of("access denied"));

        assertTrue(inspection.fileSystem().isEmpty());
        assertTrue(inspection.ntfs().isEmpty());
        assertEquals("access denied", inspection.diagnostics().getFirst());
    }
}
