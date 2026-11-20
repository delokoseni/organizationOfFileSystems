package io.github.delokoseni.service.disk;

import java.util.Map;

public final class Win32ErrorNames {
    private static final Map<Integer, String> NAMES = Map.ofEntries(
            Map.entry(1, "ERROR_INVALID_FUNCTION"),
            Map.entry(2, "ERROR_FILE_NOT_FOUND"),
            Map.entry(3, "ERROR_PATH_NOT_FOUND"),
            Map.entry(4, "ERROR_TOO_MANY_OPEN_FILES"),
            Map.entry(5, "ERROR_ACCESS_DENIED"),
            Map.entry(6, "ERROR_INVALID_HANDLE"),
            Map.entry(7, "ERROR_ARENA_TRASHED"),
            Map.entry(8, "ERROR_NOT_ENOUGH_MEMORY"),
            Map.entry(9, "ERROR_INVALID_BLOCK"),
            Map.entry(10, "ERROR_BAD_ENVIRONMENT"),
            Map.entry(11, "ERROR_BAD_FORMAT"),
            Map.entry(12, "ERROR_INVALID_ACCESS"),
            Map.entry(13, "ERROR_INVALID_DATA"),
            Map.entry(14, "ERROR_OUTOFMEMORY"),
            Map.entry(15, "ERROR_INVALID_DRIVE"),
            Map.entry(16, "ERROR_CURRENT_DIRECTORY"),
            Map.entry(17, "ERROR_NOT_SAME_DEVICE"),
            Map.entry(18, "ERROR_NO_MORE_FILES"),
            Map.entry(19, "ERROR_WRITE_PROTECT"),
            Map.entry(21, "ERROR_NOT_READY"),
            Map.entry(22, "ERROR_BAD_COMMAND"),
            Map.entry(23, "ERROR_CRC"),
            Map.entry(24, "ERROR_BAD_LENGTH"),
            Map.entry(25, "ERROR_SEEK"),
            Map.entry(26, "ERROR_NOT_DOS_DISK"),
            Map.entry(27, "ERROR_SECTOR_NOT_FOUND"),
            Map.entry(29, "ERROR_WRITE_FAULT"),
            Map.entry(30, "ERROR_READ_FAULT"),
            Map.entry(31, "ERROR_GEN_FAILURE"),
            Map.entry(32, "ERROR_SHARING_VIOLATION"),
            Map.entry(33, "ERROR_LOCK_VIOLATION"),
            Map.entry(34, "ERROR_WRONG_DISK"),
            Map.entry(38, "ERROR_HANDLE_EOF"),
            Map.entry(39, "ERROR_HANDLE_DISK_FULL"),
            Map.entry(50, "ERROR_NOT_SUPPORTED"),
            Map.entry(80, "ERROR_FILE_EXISTS"),
            Map.entry(87, "ERROR_INVALID_PARAMETER"),
            Map.entry(112, "ERROR_DISK_FULL"),
            Map.entry(116, "ERROR_DISK_CHANGE"),
            Map.entry(120, "ERROR_CALL_NOT_IMPLEMENTED"),
            Map.entry(121, "ERROR_SEM_TIMEOUT"),
            Map.entry(122, "ERROR_INSUFFICIENT_BUFFER"),
            Map.entry(123, "ERROR_INVALID_NAME"),
            Map.entry(127, "ERROR_PROC_NOT_FOUND"),
            Map.entry(170, "ERROR_BUSY"),
            Map.entry(183, "ERROR_ALREADY_EXISTS"),
            Map.entry(483, "ERROR_DEVICE_HARDWARE_ERROR"),
            Map.entry(995, "ERROR_OPERATION_ABORTED"),
            Map.entry(997, "ERROR_IO_PENDING"),
            Map.entry(1004, "ERROR_INVALID_FLAGS"),
            Map.entry(1112, "ERROR_NO_MEDIA_IN_DRIVE"),
            Map.entry(1117, "ERROR_IO_DEVICE"),
            Map.entry(1167, "ERROR_DEVICE_NOT_CONNECTED"),
            Map.entry(1784, "ERROR_INVALID_USER_BUFFER"),
            Map.entry(1816, "ERROR_NOT_ENOUGH_QUOTA"),
            Map.entry(4319, "ERROR_DEVICE_NOT_AVAILABLE"));

    private Win32ErrorNames() {
    }

    public static String name(int error) {
        return NAMES.getOrDefault(error, "UNKNOWN_WIN32_ERROR");
    }
}
