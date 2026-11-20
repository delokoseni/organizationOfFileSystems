package io.github.delokoseni.service.disk;

import io.github.delokoseni.model.DeviceIdentification;
import io.github.delokoseni.model.DiskInfo;
import io.github.delokoseni.model.PartitionStyle;
import io.github.delokoseni.model.VolumeInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalDeviceIdentificationServiceTest {
    private final ExternalDeviceIdentificationService service = new ExternalDeviceIdentificationService();

    @Test
    void identifiesOnlyExactAdataModelMappedFromD() {
        DiskInfo expected = disk(8, "ADATA SE760");
        DeviceIdentification result = service.identifyExpectedDevice(
                List.of(disk(1, "Windows SSD"), expected),
                List.of(volume("\\\\?\\Volume{d}\\", "D:\\")),
                Map.of("\\\\?\\Volume{d}\\", Set.of(8)));

        assertEquals(DeviceIdentification.Status.IDENTIFIED, result.status());
        assertEquals(8, result.disk().orElseThrow().diskNumber());
    }

    @Test
    void identifiesAdataModelWithWindowsScsiDeviceSuffix() {
        DeviceIdentification result = service.identifyExpectedDevice(
                List.of(disk(8, "ADATA SE760 SCSI Disk Device")),
                List.of(volume("\\\\?\\Volume{d}\\", "D:\\")),
                Map.of("\\\\?\\Volume{d}\\", Set.of(8)));

        assertEquals(DeviceIdentification.Status.IDENTIFIED, result.status());
        assertEquals(8, result.disk().orElseThrow().diskNumber());
    }

    @Test
    void neverFallsBackToCWhenDIsMissing() {
        DeviceIdentification result = service.identifyExpectedDevice(
                List.of(disk(0, "ADATA SE760")),
                List.of(volume("\\\\?\\Volume{c}\\", "C:\\")),
                Map.of("\\\\?\\Volume{c}\\", Set.of(0)));

        assertEquals(DeviceIdentification.Status.TARGET_VOLUME_NOT_FOUND, result.status());
        assertTrue(result.disk().isEmpty());
    }

    @Test
    void rejectsMissingMappingModelAndMismatchedModel() {
        List<VolumeInfo> dVolume = List.of(volume("\\\\?\\Volume{d}\\", "D:\\"));
        Map<String, Set<Integer>> mappedToDiskFour = Map.of("\\\\?\\Volume{d}\\", Set.of(4));

        assertEquals(DeviceIdentification.Status.DISK_MAPPING_UNAVAILABLE,
                service.identifyExpectedDevice(List.of(disk(4, "ADATA SE760")), dVolume, Map.of()).status());
        assertEquals(DeviceIdentification.Status.MODEL_UNAVAILABLE,
                service.identifyExpectedDevice(List.of(disk(4, "")), dVolume, mappedToDiskFour).status());
        assertEquals(DeviceIdentification.Status.MODEL_MISMATCH,
                service.identifyExpectedDevice(List.of(disk(4, "ADATA SE880")), dVolume, mappedToDiskFour).status());
    }

    @Test
    void rejectsAmbiguousVolumeOrDiskExtentMapping() {
        List<VolumeInfo> duplicateD = List.of(
                volume("\\\\?\\Volume{d1}\\", "D:\\"), volume("\\\\?\\Volume{d2}\\", "D:"));
        assertEquals(DeviceIdentification.Status.AMBIGUOUS_TARGET_VOLUME,
                service.identifyExpectedDevice(List.of(disk(4, "ADATA SE760")), duplicateD, Map.of()).status());

        assertEquals(DeviceIdentification.Status.AMBIGUOUS_DISK_MAPPING,
                service.identifyExpectedDevice(List.of(disk(4, "ADATA SE760")), duplicateD.subList(0, 1),
                        Map.of("\\\\?\\Volume{d1}\\", Set.of(4, 5))).status());
    }

    private static DiskInfo disk(int number, String model) {
        return new DiskInfo(number, "\\\\.\\PhysicalDrive" + number, model, "", "",
                1024, 512, 4096, true, PartitionStyle.UNKNOWN);
    }

    private static VolumeInfo volume(String path, String mountPoint) {
        return new VolumeInfo(path, List.of(mountPoint), "", "", 0, 0, 0);
    }
}
