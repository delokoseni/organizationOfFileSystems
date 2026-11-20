package io.github.delokoseni.service.disk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StorageDeviceIdentityTest {
    @Test
    void normalizesVendorAndProductForExactModelComparison() {
        assertEquals("ADATA SE760",
                new StorageDeviceIdentity(" ADATA  ", "SE760  ", null).modelName());
        assertEquals("SE760", new StorageDeviceIdentity("", "SE760", "").modelName());
    }

    @Test
    void retainsRemovableMediaFlagWithoutChangingModelIdentity() {
        var identity = new StorageDeviceIdentity("USB", "Storage", "serial", true);

        assertEquals("USB Storage", identity.modelName());
        org.junit.jupiter.api.Assertions.assertTrue(identity.removableMedia());
    }
}
