package io.github.delokoseni.service.volume;

import java.util.Locale;

public enum FileSystemType {
    NTFS,
    EXFAT,
    FAT32,
    OTHER,
    UNKNOWN;

    public static FileSystemType fromWindowsName(String name) {
        if (name == null || name.isBlank()) {
            return UNKNOWN;
        }
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "NTFS" -> NTFS;
            case "EXFAT" -> EXFAT;
            case "FAT32", "FAT" -> FAT32;
            default -> OTHER;
        };
    }
}
