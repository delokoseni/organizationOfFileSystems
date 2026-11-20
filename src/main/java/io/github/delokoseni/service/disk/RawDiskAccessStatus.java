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
            case DISCOVERED -> "Discovered; raw read not attempted";
            case READ_ACCESS_AVAILABLE -> "Read access available";
            case ACCESS_DENIED -> "Access denied";
            case DEVICE_DISCONNECTED -> "Device disconnected";
            case INFORMATION_UNAVAILABLE -> "Information unavailable";
        };
    }
}
