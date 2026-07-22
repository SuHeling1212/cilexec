package com.follarce.domain.port;

/** Transaction isolation choices exposed to application services. */
public enum Isolation {
    READ_COMMITTED,
    SERIALIZABLE
}
