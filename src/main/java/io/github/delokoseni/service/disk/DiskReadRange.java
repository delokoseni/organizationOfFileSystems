package io.github.delokoseni.service.disk;

public final class DiskReadRange {
    private DiskReadRange() {
    }

    public static long validate(long offset, int length, long diskSize) {
        if (offset < 0 || length <= 0 || diskSize < 0) {
            throw new IllegalArgumentException("Disk size and read offset cannot be negative; "
                    + "read length must be positive");
        }
        long end;
        try {
            end = Math.addExact(offset, length);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Read range overflows", e);
        }
        if (end > diskSize) {
            throw new IllegalArgumentException("Read range extends beyond disk capacity");
        }
        return end;
    }
}
