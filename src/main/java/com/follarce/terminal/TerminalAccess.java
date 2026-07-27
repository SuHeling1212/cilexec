package com.follarce.terminal;

import com.follarce.domain.auth.UserAccount;

import java.util.Optional;

public interface TerminalAccess {
    Optional<UserAccount> login(String username, char[] password);

    UserAccount register(String username, char[] password);

    UserAccount register(String username, char[] password, char[] adminPassword);
}
