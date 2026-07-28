package com.follarce.extension.api;

import java.util.Map;
import java.util.Optional;

/** Extension-private state stored inside the durable FCL continuation. */
public interface ExtensionState {
    boolean contains(String key);

    Optional<Object> find(String key);

    void put(String key, Object value);

    Optional<Object> remove(String key);

    Map<String, Object> snapshot();
}
