package io.github.delokoseni.service.disk;

import java.io.IOException;

public final class DiskOpenException extends IOException {
    private final String devicePath;
    private final int desiredAccess;
    private final int shareMode;
    private final int creationDisposition;
    private final int flagsAndAttributes;
    private final int win32Error;

    public DiskOpenException(
            String devicePath, int desiredAccess, int shareMode,
            int creationDisposition, int flagsAndAttributes, int win32Error) {
        super("Не удалось выполнить CreateFileW для " + devicePath
                + " (desiredAccess=0x" + Integer.toHexString(desiredAccess)
                + ", shareMode=0x" + Integer.toHexString(shareMode)
                + ", creationDisposition=" + creationDisposition
                + ", flagsAndAttributes=0x" + Integer.toHexString(flagsAndAttributes)
                + ", ошибка Win32 " + win32Error + " " + Win32ErrorNames.name(win32Error) + ")");
        this.devicePath = devicePath;
        this.desiredAccess = desiredAccess;
        this.shareMode = shareMode;
        this.creationDisposition = creationDisposition;
        this.flagsAndAttributes = flagsAndAttributes;
        this.win32Error = win32Error;
    }

    public String devicePath() { return devicePath; }
    public int desiredAccess() { return desiredAccess; }
    public int shareMode() { return shareMode; }
    public int creationDisposition() { return creationDisposition; }
    public int flagsAndAttributes() { return flagsAndAttributes; }
    public int win32Error() { return win32Error; }
}
