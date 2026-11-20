package io.github.delokoseni.service.parser;

import io.github.delokoseni.model.PartitionStyle;
import io.github.delokoseni.service.disk.WindowsPartitionInfo;
import io.github.delokoseni.service.disk.WindowsPartitionLayout;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PartitionComparisonServiceTest {
    @Test
    void reportsDifferencesWithoutChangingEitherLayout() {
        PartitionRecord parsed = new PartitionRecord("GPT 1", 10, 19, 5120, 5120,
                false, 0, "type-a", "unique-a", 0, "DATA");
        WindowsPartitionLayout windows = new WindowsPartitionLayout(PartitionStyle.MBR,
                List.of(new WindowsPartitionInfo(2, 4096, 1024, 0x07, "", "")));

        List<String> differences = new PartitionComparisonService().compare(
                PartitionStyle.GPT, List.of(parsed), windows);

        assertTrue(differences.stream().anyMatch(text -> text.contains("style differs")));
        assertTrue(differences.stream().anyMatch(text -> text.contains("partition number")));
        assertTrue(differences.stream().anyMatch(text -> text.contains("starting offset differs")));
        assertTrue(differences.stream().anyMatch(text -> text.contains("length differs")));
    }
}
