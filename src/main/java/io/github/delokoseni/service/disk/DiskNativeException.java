package io.github.delokoseni.service.disk;

import java.io.IOException;

public final class DiskNativeException extends IOException {
    private final String operation;
    private final String devicePath;
    private final int win32Error;

    public DiskNativeException(String operation, String devicePath, int win32Error) {
        super("Сбой " + operation + " для " + devicePath
                + " (дескриптор открыт с доступом GENERIC_READ, shareMode=0x3, "
                + "creationDisposition=3, flagsAndAttributes=0; ошибка Win32 "
                + win32Error + " " + Win32ErrorNames.name(win32Error) + ")");
        this.operation = operation;
        this.devicePath = devicePath;
        this.win32Error = win32Error;
    }

    public String operation() { return operation; }
    public String devicePath() { return devicePath; }
    public int win32Error() { return win32Error; }
    public String win32ErrorName() { return Win32ErrorNames.name(win32Error); }
}
