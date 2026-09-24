package com.reconcileflow.idempotent;

/** Must be routed to retry, never treated as an acknowledged duplicate. */
public class RetryableIdempotencyException extends RuntimeException {
    public RetryableIdempotencyException(String message) { super(message); }
}
