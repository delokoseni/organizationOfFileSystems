package io.github.delokoseni.service.parser;

public record PartitionRecord(
        String source,
        long firstLba,
        long lastLbaInclusive,
        long startingOffsetBytes,
        long lengthBytes,
        boolean bootable,
        int mbrType,
        String typeGuid,
        String uniqueGuid,
        long attributes,
        String name) {
}
