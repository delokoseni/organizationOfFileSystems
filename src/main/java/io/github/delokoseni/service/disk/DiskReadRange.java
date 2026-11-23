package io.github.delokoseni.service.disk;

public final class DiskReadRange {
    private DiskReadRange() {
    }

    public static long validate(long offset, int length, long diskSize) {
        if (offset < 0 || length <= 0 || diskSize < 0) {
            throw new IllegalArgumentException("Размер диска и смещение чтения не могут быть отрицательными; "
                    + "длина чтения должна быть положительной.");
        }
        long end;
        try {
            end = Math.addExact(offset, length);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Переполнение диапазона чтения.", e);
        }
        if (end > diskSize) {
            throw new IllegalArgumentException("Диапазон чтения выходит за ёмкость диска.");
        }
        return end;
    }
}
