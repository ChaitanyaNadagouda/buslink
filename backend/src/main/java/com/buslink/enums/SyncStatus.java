package com.buslink.enums;

/**
 * Final: SYNCED, DISPUTED, REJECTED. A re-upload returns the stored result.
 * Retryable: FAILED (technical error), by the retry job while under the attempt cap, or by any re-upload.
 */
public enum SyncStatus {
    PENDING,
    PROCESSING,
    SYNCED,
    FAILED,
    DISPUTED,
    /** The event's own data is invalid (too old, bus mismatch, bad QR, ...). Retrying can't change it. */
    REJECTED
}
