package com.follarce.market.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenStoreTest {
    @TempDir Path temporary;

    private Path tokensFile() {
        return temporary.resolve("tokens.json");
    }

    @Test
    void addGeneratesAOneTimePlaintextAndPersistsOnlyTheDigest() throws Exception {
        TokenStore store = new TokenStore(tokensFile());

        String plaintext = store.add("alice");

        assertTrue(plaintext.matches("[0-9a-f]{64}"), "token must be 64 hex characters");
        assertTrue(store.isValid(plaintext));
        String stored = Files.readString(tokensFile());
        assertFalse(stored.contains(plaintext),
                "the plaintext token must never be persisted: " + stored);
        assertTrue(stored.contains("\"alice\""), stored);
    }

    @Test
    void reloadsTokensFromDiskAndRejectsUnknownOrWrongTokens() throws Exception {
        TokenStore store = new TokenStore(tokensFile());
        String alice = store.add("alice");
        String bob = store.add("bob");

        TokenStore reloaded = new TokenStore(tokensFile());

        assertTrue(reloaded.isValid(alice));
        assertTrue(reloaded.isValid(bob));
        assertFalse(reloaded.isValid("f".repeat(64)));
        assertFalse(reloaded.isValid(""));
        assertFalse(reloaded.isValid(null));
        assertEquals(Set.of("alice", "bob"), reloaded.names());
    }

    @Test
    void removeDeletesOnlyTheNamedToken() throws Exception {
        TokenStore store = new TokenStore(tokensFile());
        String alice = store.add("alice");
        store.add("bob");

        assertTrue(store.remove("alice"));
        assertFalse(store.remove("alice"));

        assertFalse(store.isValid(alice));
        assertEquals(Set.of("bob"), store.names());
    }

    @Test
    void restrictsTheTokenFileToTheOwner() throws Exception {
        TokenStore store = new TokenStore(tokensFile());
        store.add("alice");

        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(tokensFile());
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                permissions);
    }

    @Test
    void rejectsDuplicateNamesAndInvalidNames() throws Exception {
        TokenStore store = new TokenStore(tokensFile());
        store.add("alice");

        assertThrows(IllegalArgumentException.class, () -> store.add("alice"));
        assertThrows(IllegalArgumentException.class, () -> store.add("a b"));
        assertThrows(IllegalArgumentException.class, () -> store.add(""));
    }

    @Test
    void picksUpTokensAddedWhileTheStoreIsInUse() throws Exception {
        TokenStore store = new TokenStore(tokensFile());
        assertFalse(store.isValid("a".repeat(64)));

        TokenStore other = new TokenStore(tokensFile());
        String plaintext = other.add("late");

        assertTrue(store.isValid(plaintext),
                "a running server must accept tokens created after it started");
        other.remove("late");
        assertFalse(store.isValid(plaintext),
                "removed tokens must stop working without a restart");
    }
}
