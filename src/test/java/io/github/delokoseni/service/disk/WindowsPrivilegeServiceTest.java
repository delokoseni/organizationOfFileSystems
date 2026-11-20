package io.github.delokoseni.service.disk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsPrivilegeServiceTest {
    @Test
    void reportsInjectedAdministratorMembershipWithoutLaunchingOrElevating() throws Exception {
        assertTrue(new WindowsPrivilegeService(() -> true).isAdministrator());
        assertFalse(new WindowsPrivilegeService(() -> false).isAdministrator());
    }
}
