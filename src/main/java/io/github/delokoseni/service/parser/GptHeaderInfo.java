package io.github.delokoseni.service.parser;

public record GptHeaderInfo(
        long lba,
        boolean signatureValid,
        boolean headerCrcValid,
        long revision,
        long headerSize,
        long storedHeaderCrc32,
        long currentLba,
        long backupLba,
        long firstUsableLba,
        long lastUsableLba,
        String diskGuid,
        long entriesLba,
        long entryCount,
        long entrySize,
        long storedArrayCrc32,
        boolean arrayCrcValid) {

    public boolean structurallyValid() {
        return signatureValid && headerCrcValid && arrayCrcValid;
    }

    public boolean validAt(long expectedLba, long expectedBackupLba, long diskSectors, int sectorSize) {
        if (!structurallyValid() || lba != expectedLba || currentLba != expectedLba
                || backupLba != expectedBackupLba || firstUsableLba < 2
                || firstUsableLba > lastUsableLba || lastUsableLba >= diskSectors - 1
                || entryCount <= 0 || entrySize < 128 || entrySize % 8 != 0) {
            return false;
        }
        try {
            long arrayBytes = Math.multiplyExact(entryCount, entrySize);
            long arraySectors = Math.addExact(arrayBytes, sectorSize - 1L) / sectorSize;
            long arrayEnd = Math.addExact(entriesLba, arraySectors);
            if (arrayEnd > diskSectors || entriesLba == 0
                    || expectedLba == 1 && (entriesLba <= 1 || arrayEnd > firstUsableLba)
                    || expectedLba == diskSectors - 1
                    && (entriesLba <= lastUsableLba || arrayEnd > expectedLba)) {
                return false;
            }
            return true;
        } catch (ArithmeticException e) {
            return false;
        }
    }
}
