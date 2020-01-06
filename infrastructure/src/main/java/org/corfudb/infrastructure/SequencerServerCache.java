package org.corfudb.infrastructure;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.runtime.view.Address;
import java.util.Comparator;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sequencer server cache.
 * Contains transaction conflict-resolution data structures.
 * <p>
 * The SequencerServer use its own thread/s. To guarantee correct tx conflict-resolution,
 * the {@link SequencerServerCache#cacheHash} must be updated
 * along with {@link SequencerServerCache#maxConflictWildcard} at the same time (atomically) to prevent race condition
 * when the conflict stream is already evicted from the cache but `maxConflictWildcard` is not updated yet,
 * which can cause situation when sequencer let the transaction go but the tx has to be cancelled.
 * <p>
 * SequencerServerCache achieves consistency by using single threaded cache. It's done by following code:
 * `.executor(Runnable::run)`
 */
@Slf4j
public class SequencerServerCache {
    /**
     * TX conflict-resolution information:
     * <p>
     * a cache of recent conflict keys and their latest global-log position.
     */
    //As the sequencer cache is used by a single thread, it is safe to use hashmap.
    private final HashMap<ConflictTxStream, Long> cacheHash;
    private final PriorityQueue<ConflictTxStreamEntry> cacheEntries; //sorted according to address
    private final int cacheSize;
    private long maxAddress = Address.NOT_FOUND; //the max sequence in cacheEntries.
    /**
     * A "wildcard" representing the maximal update timestamp of
     * all the conflict keys which were evicted from the cache
     */
    @Getter
    private long maxConflictWildcard = Address.NOT_FOUND;

    /**
     * maxConflictNewSequencer represents the max update timestamp of all the conflict keys
     * which were evicted from the cache by the time this server is elected
     * the primary sequencer. This means that any snapshot timestamp below this
     * actual threshold would abort due to NEW_SEQUENCER cause.
     */
    @Getter
    private long maxConflictNewSequencer = Address.NOT_FOUND;

    /**
     * The cache limited by size.
     * For a synchronous cache we are using a same-thread executor (Runnable::run)
     * https://github.com/ben-manes/caffeine/issues/90
     *
     * @param cacheSize cache size
     */

    public SequencerServerCache(int cacheSize) {
        this.cacheSize = cacheSize;
        cacheHash = new HashMap<>();
        cacheEntries = new PriorityQueue<> (cacheSize, new ConflictTxStreamEntryComparator());
    }

    /**
     * Returns the value associated with the {@code key} in this cache,
     * or {@code null} if there is no cached value for the {@code key}.
     *
     * @param conflictKey conflict stream
     * @return global address
     */
    public Long getIfPresent(ConflictTxStream conflictKey) {
        return cacheHash.get(conflictKey);
    }

    private long firstAddress() {
        if (cacheEntries.peek() == null)
            return Address.NOT_FOUND;
        return cacheEntries.peek().txVersion;
    }

    /**
     * Invalidate the first record with the minAddress.
     */
    private void invalidateFirst() {
        ConflictTxStreamEntry entry = cacheEntries.poll();
        cacheHash.remove(entry.conflictTxStream);
        maxConflictWildcard = Math.max(entry.txVersion, maxConflictWildcard);
        log.trace("Updating maxConflictWildcard. Old = '{}', new ='{}' conflictParam = '{}'.",
                maxConflictWildcard, entry.txVersion, entry.conflictTxStream);
    }

    /**
     * Invalidate all records up to a trim mark (not included).
     *
     * @param trimMark trim mark
     * */
    public void invalidateUpTo(long trimMark) {
        log.debug("Invalidate sequencer cache. Trim mark: {}", trimMark);
        AtomicLong entries = new AtomicLong();
        long first;

        while((first = firstAddress()) != Address.NOT_FOUND && first < trimMark) {
            invalidateFirst();
            entries.incrementAndGet();
        }
        log.info("Invalidated entries: {}", entries.get());
    }

    /**
     * The cache size
     *
     * @return cache size
     */
    public long size() {
        return cacheHash.size();
    }

    /**
     * Put a value in the cache
     *
     * @param conflictStream conflict stream
     * @param newTail        global tail
     */
    public void put(ConflictTxStream conflictStream, long newTail) {
        if (cacheHash.size() == cacheSize) {
            ConflictTxStreamEntry entry = cacheEntries.peek();
            invalidateUpTo(firstAddress() + 1);
        }

        ConflictTxStreamEntry entry = new ConflictTxStreamEntry(conflictStream, newTail);
        cacheEntries.add(entry);
        cacheHash.put(conflictStream, entry.txVersion);
        maxAddress = Math.max(newTail, maxAddress);
    }

    /**
     * Discard all entries in the cache
     */
    public void invalidateAll() {
        log.info("Invalidate all entries in sequencer server cache and update maxConflictWildcard to {}", maxAddress);
        cacheHash.clear();
        cacheEntries.clear();
        maxConflictWildcard = maxAddress;
    }

    /**
     * Update max conflict wildcard by a new address
     *
     * @param newMaxConflictWildcard new conflict wildcard
     */
    public void updateMaxConflictAddress(long newMaxConflictWildcard) {
        log.info("updateMaxConflictAddress, new address: {}", newMaxConflictWildcard);
        maxConflictWildcard = newMaxConflictWildcard;
        maxConflictNewSequencer = newMaxConflictWildcard;
        maxAddress = newMaxConflictWildcard;
    }

    /**
     * Contains the conflict hash code for a stream ID and conflict param.
     */
    @EqualsAndHashCode
    public static class ConflictTxStream {
        private final UUID streamId;
        private final byte[] conflictParam;

        public ConflictTxStream(UUID streamId, byte[] conflictParam) {
            this.streamId = streamId;
            this.conflictParam = conflictParam;
        }

        @Override
        public String toString() {
            return streamId.toString() + conflictParam;
        }
    }

    static class ConflictTxStreamEntry {
        private final ConflictTxStream conflictTxStream;
        private final long txVersion;

        public ConflictTxStreamEntry(ConflictTxStream stream, long address) {
            conflictTxStream = stream;
            txVersion = address;
        }
    }

    class ConflictTxStreamEntryComparator implements Comparator<ConflictTxStreamEntry> {
        // Overriding compare()method of Comparator for ascending order
        public int compare(ConflictTxStreamEntry s1, ConflictTxStreamEntry s2) {
            return Long.compare(s1.txVersion, s2.txVersion);
        }
    }
}
