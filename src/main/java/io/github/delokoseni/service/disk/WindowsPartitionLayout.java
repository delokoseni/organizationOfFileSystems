package io.github.delokoseni.service.disk;

import io.github.delokoseni.model.PartitionStyle;

import java.util.List;
import java.util.Objects;

public record WindowsPartitionLayout(PartitionStyle style, List<WindowsPartitionInfo> partitions) {
    public WindowsPartitionLayout {
        style = Objects.requireNonNullElse(style, PartitionStyle.UNKNOWN);
        partitions = List.copyOf(partitions);
    }
}
