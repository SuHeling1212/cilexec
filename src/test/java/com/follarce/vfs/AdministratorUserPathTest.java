package com.follarce.vfs;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdministratorUserPathTest {
    @Test
    void repeatedAdministratorHomesResolveToTheSameVirtualUsersDirectory() {
        UUID local = UUID.randomUUID();
        UUID alice = UUID.randomUUID();
        Map<String, UUID> users = Map.of("local", local, "alice", alice);

        for (String path : new String[]{"/Users", "/Users/local/Users",
                "/Users/local/Users/local/Users"}) {
            assertEquals(new AdministratorUserPath.Resolved(local, "/Users"),
                    AdministratorUserPath.route(path, local, users::get));
        }
        assertEquals(new AdministratorUserPath.Resolved(alice, "/notes"),
                AdministratorUserPath.route("/Users/local/Users/alice/notes", local,
                        users::get));
    }
}
