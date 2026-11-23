package io.github.delokoseni.service.volume;

import io.github.delokoseni.model.DiskExtent;
import io.github.delokoseni.service.disk.WindowsPartitionInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolumePartitionMappingServiceTest {
    private final VolumePartitionMappingService service = new VolumePartitionMappingService();

    @Test
    void mapsMultipleExtentsUsingExplicitDiskNumbersAndPartitionBoundaries() {
        List<DiskExtent> extents = List.of(
                new DiskExtent(8, 1024, 2048),
                new DiskExtent(2, 4096, 1024));
        Map<Integer, List<WindowsPartitionInfo>> partitions = Map.of(
                8, List.of(partition(1, 512, 4096)),
                2, List.of(partition(4, 4096, 8192)));

        var mappings = service.map(extents, partitions);

        assertEquals(2, mappings.size());
        assertTrue(mappings.get(0).isUnambiguous());
        assertEquals(1, mappings.get(0).candidatePartitions().getFirst().partitionNumber());
        assertTrue(mappings.get(1).isUnambiguous());
        assertEquals(4, mappings.get(1).candidatePartitions().getFirst().partitionNumber());
    }

    @Test
    void reportsMissingOrAmbiguousPartitionMapping() {
        var missing = service.map(List.of(new DiskExtent(5, 0, 512)), Map.of()).getFirst();
        assertFalse(missing.isUnambiguous());

        var ambiguous = service.map(List.of(new DiskExtent(5, 1024, 1024)),
                Map.of(5, List.of(partition(1, 0, 4096), partition(2, 0, 4096)))).getFirst();
        assertFalse(ambiguous.isUnambiguous());
        assertTrue(ambiguous.diagnostic().contains("неоднозначно"));
    }

    @Test
    void reportsOverflowingExtentRange() {
        var mapping = service.map(List.of(new DiskExtent(5, Long.MAX_VALUE, 1)), Map.of()).getFirst();
        assertTrue(mapping.diagnostic().contains("переполняются"));
    }

    private static WindowsPartitionInfo partition(int number, long offset, long length) {
        return new WindowsPartitionInfo(number, offset, length, 0, "", "");
    }
}
