package com.reconcileflow.idempotent;

import java.time.Duration;

public interface IdempotencyStore {
    enum Claim { ACQUIRED, BUSY, COMPLETED }
    Claim claim(String key, String owner, Duration lease);
    boolean complete(String key, String owner, Duration retention);
    boolean release(String key, String owner);
}
