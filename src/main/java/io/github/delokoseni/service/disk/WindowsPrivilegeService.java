package io.github.delokoseni.service.disk;

import com.sun.jna.Native;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.io.IOException;
import java.util.Locale;
import java.util.function.BooleanSupplier;

public final class WindowsPrivilegeService {
    private final BooleanSupplier administratorCheck;

    public WindowsPrivilegeService() {
        this(() -> Shell32Holder.INSTANCE.IsUserAnAdmin());
    }

    WindowsPrivilegeService(BooleanSupplier administratorCheck) {
        this.administratorCheck = administratorCheck;
    }

    public boolean isAdministrator() throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return false;
        }

        try {
            return administratorCheck.getAsBoolean();
        } catch (LinkageError e) {
            throw new IOException("Could not determine Windows administrator membership", e);
        }
    }

    public String accessDeniedGuidance() throws IOException {
        return isAdministrator()
                ? "The process is in the administrator group, but device ACLs or policy still denied access."
                : "Raw physical-disk reads commonly require an elevated administrator token. "
                + "Restart explicitly as administrator if appropriate; the application did not elevate itself.";
    }

    private interface Shell32 extends StdCallLibrary {
        boolean IsUserAnAdmin();
    }

    private static final class Shell32Holder {
        private static final Shell32 INSTANCE =
                Native.load("shell32", Shell32.class, W32APIOptions.UNICODE_OPTIONS);
    }
}
