package io.github.imcinq.puremetal.backend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** One native retain per shared state, released when its last Java owner closes. */
final class MetalResourceCache<K> implements AutoCloseable {
    private final class Entry {
        final ArrayList<K> keys = new ArrayList<>();
        final long handle;
        int owners;

        Entry(final K key, final long handle, final int owners) {
            this.keys.add(key);
            this.handle = handle;
            this.owners = owners;
        }
    }

    private static final class Creation {
        int claims = 1;
        boolean done;
        long handle;
        Throwable failure;
    }

    private final Map<K, Entry> entries = new HashMap<>();
    private final Map<Long, Entry> handles = new HashMap<>();
    private final Map<K, Creation> pending = new HashMap<>();
    private boolean closed;

    long acquire(final K key, final LongSupplier create) {
        Creation creation;
        boolean creator;
        synchronized (this) {
            if (this.closed) throw new IllegalStateException("Metal resource cache is closed");
            Entry entry = this.entries.get(key);
            if (entry != null) {
                entry.owners++;
                return entry.handle;
            }
            creation = this.pending.get(key);
            creator = creation == null;
            if (creator) {
                creation = new Creation();
                try {
                    this.pending.put(key, creation);
                } catch (RuntimeException | Error failure) {
                    // HashMap can insert before a resize allocation fails; do not leave an orphaned creator.
                    this.pending.remove(key, creation);
                    throw failure;
                }
            } else {
                // Reserve ownership before waiting, so the creator cannot close the state out from under us.
                creation.claims++;
            }
        }
        if (!creator) return this.awaitCreation(creation);

        long created = 0L;
        boolean adopted = false;
        try {
            // Different keys still compile concurrently, without holding the cache monitor.
            created = create.getAsLong();
            if (created == 0L) throw new IllegalStateException("Could not create shared Metal state");
            synchronized (this) {
                if (this.closed) throw new IllegalStateException("Metal resource cache closed during creation");
                Entry entry = this.handles.get(created);
                if (entry == null) {
                    entry = new Entry(key, created, creation.claims);
                    try {
                        this.entries.put(key, entry);
                        this.handles.put(created, entry);
                    } catch (RuntimeException | Error failure) {
                        // Roll back while still holding the monitor, before anyone can acquire the failed entry.
                        this.entries.remove(key, entry);
                        this.handles.remove(created, entry);
                        throw failure;
                    }
                    adopted = true;
                } else {
                    // The driver may intern equivalent states even when their Java keys differ.
                    try {
                        entry.keys.add(key);
                        this.entries.put(key, entry);
                    } catch (RuntimeException | Error failure) {
                        this.entries.remove(key, entry);
                        entry.keys.remove(key);
                        throw failure;
                    }
                    entry.owners += creation.claims;
                    MetalNative.release(created);
                    created = 0L;
                }
                creation.handle = entry.handle;
                creation.done = true;
                this.pending.remove(key);
                this.notifyAll();
                return entry.handle;
            }
        } catch (RuntimeException | Error failure) {
            try {
                if (created != 0L && !adopted) MetalNative.release(created);
            } finally {
                synchronized (this) {
                    creation.failure = failure;
                    creation.done = true;
                    this.pending.remove(key);
                    this.notifyAll();
                }
            }
            throw failure;
        }
    }

    private long awaitCreation(final Creation creation) {
        boolean interrupted = false;
        try {
            synchronized (this) {
                while (!creation.done && !this.closed) {
                    try {
                        this.wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (this.closed) throw new IllegalStateException("Metal resource cache is closed");
                if (creation.failure instanceof RuntimeException failure) throw failure;
                if (creation.failure instanceof Error failure) throw failure;
                return creation.handle;
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    void release(final long handle) {
        if (handle == 0L) return;
        synchronized (this) {
            Entry entry = this.handles.get(handle);
            if (entry == null) {
                if (this.closed) return;
                throw new IllegalStateException("Metal state is not owned by this cache");
            }
            if (--entry.owners != 0) return;
            entry.keys.forEach(this.entries::remove);
            this.handles.remove(handle);
        }
        MetalNative.release(handle);
    }

    @Override
    public void close() {
        ArrayList<Long> remaining;
        boolean interrupted = false;
        synchronized (this) {
            if (this.closed) return;
            this.closed = true;
            this.notifyAll();
            // Native creation must finish (including release on failure) before the device can be destroyed.
            while (!this.pending.isEmpty()) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            remaining = new ArrayList<>(this.handles.keySet());
            this.entries.clear();
            this.handles.clear();
        }
        try {
            remaining.forEach(MetalNative::release);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
