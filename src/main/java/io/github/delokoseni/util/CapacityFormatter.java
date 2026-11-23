package io.github.delokoseni.util;

import java.math.BigInteger;

public final class CapacityFormatter {
    private static final BigInteger TEN = BigInteger.TEN;
    private static final String[] UNITS = {"B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB"};

    private CapacityFormatter() {
    }

    public static String format(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("Ёмкость не может быть отрицательной.");
        }
        if (bytes < 1024) {
            return bytes + " B";
        }

        BigInteger value = BigInteger.valueOf(bytes);
        BigInteger unit = BigInteger.ONE;
        int unitIndex = 0;
        while (unitIndex < UNITS.length - 1
                && value.compareTo(unit.multiply(BigInteger.valueOf(1024))) >= 0) {
            unit = unit.multiply(BigInteger.valueOf(1024));
            unitIndex++;
        }

        BigInteger tenths = value.multiply(TEN).add(unit.divide(BigInteger.TWO))
                .divide(unit);
        BigInteger[] wholeAndFraction = tenths.divideAndRemainder(TEN);
        return wholeAndFraction[0] + "." + wholeAndFraction[1] + " " + UNITS[unitIndex];
    }
}
