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
}
