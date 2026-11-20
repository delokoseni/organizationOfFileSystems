package io.github.delokoseni.service.disk;

import java.util.Objects;

public record StorageDeviceIdentity(
        String vendor, String product, String serialNumber, boolean removableMedia) {
    public StorageDeviceIdentity(String vendor, String product, String serialNumber) {
        this(vendor, product, serialNumber, false);
    }

    public StorageDeviceIdentity {
        vendor = Objects.requireNonNullElse(vendor, "").trim();
        product = Objects.requireNonNullElse(product, "").trim();
        serialNumber = Objects.requireNonNullElse(serialNumber, "").trim();
    }

    public String modelName() {
        return (vendor + " " + product).trim().replaceAll("\\s+", " ");
    }
}
