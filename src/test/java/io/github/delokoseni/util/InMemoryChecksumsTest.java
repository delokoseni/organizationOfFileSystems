package io.github.delokoseni.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryChecksumsTest {
    @Test
    void calculatesCrc32ForInMemoryBytes() {
        assertEquals(0xcbf43926L,
                InMemoryChecksums.crc32("123456789".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void calculatesSha256ForInMemoryBytes() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                InMemoryChecksums.sha256("abc".getBytes(StandardCharsets.US_ASCII)));
    }
}
