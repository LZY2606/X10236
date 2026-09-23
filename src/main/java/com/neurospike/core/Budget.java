package com.neurospike.core;

/** A countable resource budget for ingest/import/compute loops. */
public final class Budget {
    private long remaining;

    public Budget(long maxOperations) {
        this.remaining = maxOperations;
    }

    public boolean trySpend(long n) {
        if (remaining < n) return false;
        remaining -= n;
        return true;
    }

    public boolean trySpend() {
        return trySpend(1);
    }

    public long remaining() {
        return remaining;
    }

    public static Budget unlimited() {
        return new Budget(Long.MAX_VALUE);
    }
}
