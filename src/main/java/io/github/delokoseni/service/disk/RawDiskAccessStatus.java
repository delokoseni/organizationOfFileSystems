package io.github.delokoseni.service.disk;

public enum RawDiskAccessStatus {
    DISCOVERED,
    READ_ACCESS_AVAILABLE,
    ACCESS_DENIED,
    DEVICE_DISCONNECTED,
    INFORMATION_UNAVAILABLE;

    public static RawDiskAccessStatus fromWin32Error(int error) {
        return switch (error) {
            case 5, 1314 -> ACCESS_DENIED;
            case 2, 6, 21 -> DEVICE_DISCONNECTED;
            default -> INFORMATION_UNAVAILABLE;
        };
    }

    public String displayName() {
        return switch (this) {
            case DISCOVERED -> "Обнаружен; чтение не выполнялось";
            case READ_ACCESS_AVAILABLE -> "Доступно чтение";
            case ACCESS_DENIED -> "Доступ запрещён";
            case DEVICE_DISCONNECTED -> "Устройство отключено";
            case INFORMATION_UNAVAILABLE -> "Сведения недоступны";
        };
    }
}
