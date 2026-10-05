package io.github.shabbirflow.ironclad.storage;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Random;

/**
 * IN ONE LINE: a sorted map from byte[] keys to byte[] values, built from linked
 * lists and coin flips, which is what the memtable will be made of.
 *
 * Single-threaded for now, deliberately: a wrong answer here should have exactly
 * one possible cause. Concurrency is its own milestone, with these tests and the
 * JMH benchmark already in place to show what it costs.
 *
 * Invariant: walking lane 0 from the head visits every live key exactly once, in
 * ascending unsigned byte order.
 */
public final class SkipList implements Iterable<SkipList.Entry> {

    /**
     * A node reaching every lane would need 12 pointers; at p = 1/2 that supports
     * a few million entries before search degrades. The memtable flushes long
     * before then.
     */
    private static final int MAX_HEIGHT = 12;

    /** Probability of promoting a node one more lane. */
    private static final double PROMOTE = 0.5;

    /**
     * Rough per-entry cost beyond the key and value bytes: object header, the key
     * and value array headers, and the pointer tower. An estimate on purpose; the
     * flush threshold only needs to be roughly right, and measuring exactly would
     * cost more than it is worth.
     */
    public static final int ENTRY_OVERHEAD_BYTES = 64;

    /**
     * One entry, handed to callers during iteration. A record, so nobody can
     * reach in and mutate the list through it.
     */
    public record Entry(byte[] key, byte[] value) {}

    private static final class Node {
        final byte[] key;
        byte[] value;             // replaced in place when a key is put again
        final Node[] next;        // one slot per lane this node reaches

        Node(byte[] key, byte[] value, int height) {
            this.key = key;
            this.value = value;
            this.next = new Node[height];
        }
    }

    /** Sentinel standing left of every key, with a pointer in every lane. */
    private final Node head = new Node(null, null, MAX_HEIGHT);

    private final Random random;
    private int height = 1;       // tallest tower currently in use
    private int size;
    private long sizeInBytes;

    public SkipList() {
        this(new Random());
    }

    /** Takes a Random so a failing random test can be replayed exactly. */
    public SkipList(Random random) {
        this.random = random;
    }

    public int size() {
        return size;
    }

    public long sizeInBytes() {
        return sizeInBytes;
    }

    /**
     * Unsigned comparison, because Java's byte is signed: 0x80 is -128, so a
     * plain compare would sort it before 0x01. On-disk keys must be in unsigned
     * byte order, so the memtable has to agree with the files it produces.
     */
    private static int compare(byte[] a, byte[] b) {
        return Arrays.compareUnsigned(a, b);
    }

    /**
     * Coin flips, capped at MAX_HEIGHT. Each node decides its own height without
     * consulting anything else, which is why there is no rebalancing in this
     * class at all.
     */
    private int randomHeight() {
        int h = 1;
        while (h < MAX_HEIGHT && random.nextDouble() < PROMOTE) {
            h++;
        }
        return h;
    }

    /**
     * For each lane, the last node whose key is strictly less than the target:
     * the nodes whose pointers have to change if we splice something in here.
     *
     * This is the one loop that matters. Start in the highest lane at the head,
     * step right while the next key is still smaller, drop a lane when it is not.
     * Dropping a lane does not move sideways: it follows a lower pointer of the
     * node we are already standing on.
     */
    private Node[] findPredecessors(byte[] key) {
        // One array allocated per call, in the hot path. Known cost: milestone 1.9
        // can reuse a scratch array once there are numbers to justify it.
        Node[] predecessors = new Node[MAX_HEIGHT];
        Node current = head;
        for (int lane = height - 1; lane >= 0; lane--) {
            while (current.next[lane] != null && compare(current.next[lane].key, key) < 0) {
                current = current.next[lane];
            }
            predecessors[lane] = current;
        }
        for (int lane = height; lane < MAX_HEIGHT; lane++) {
            predecessors[lane] = head;      // lanes nobody reaches yet
        }
        return predecessors;
    }

    /** The value for this key, or null when it is not here. */
    public byte[] get(byte[] key) {
        Node current = head;
        for (int lane = height - 1; lane >= 0; lane--) {
            while (current.next[lane] != null && compare(current.next[lane].key, key) < 0) {
                current = current.next[lane];
            }
        }
        // current is now the last node before the key; the key itself, if present,
        // is the very next node in lane 0.
        Node candidate = current.next[0];
        return candidate != null && compare(candidate.key, key) == 0 ? candidate.value : null;
    }

    /** Inserts the key, or replaces its value if it is already here. */
    public void put(byte[] key, byte[] value) {
        Node[] predecessors = findPredecessors(key);
        Node existing = predecessors[0].next[0];

        if (existing != null && compare(existing.key, key) == 0) {
            sizeInBytes += value.length - existing.value.length;
            existing.value = value;
            return;
        }

        int newHeight = randomHeight();
        if (newHeight > height) {
            height = newHeight;             // the head already has pointers up here
        }

        Node node = new Node(key, value, newHeight);
        for (int lane = 0; lane < newHeight; lane++) {
            node.next[lane] = predecessors[lane].next[lane];
            predecessors[lane].next[lane] = node;
        }

        size++;
        sizeInBytes += key.length + value.length + ENTRY_OVERHEAD_BYTES;
    }

    /**
     * Unlinks the key, returning true if it was here.
     *
     * Physical removal, not a tombstone: this class is a sorted map and knows
     * nothing about shadowing values in older files. The engine writes tombstones
     * at the layer above, in milestone 1.5, because a delete has to hide values
     * that already reached disk.
     *
     * Bugs live here. The node must be unlinked from every lane it reaches and no
     * further: predecessors[lane].next[lane] is the target only for lanes the
     * target actually occupies, which is why the loop bounds are the target's own
     * height rather than the list's.
     */
    public boolean remove(byte[] key) {
        Node[] predecessors = findPredecessors(key);
        Node target = predecessors[0].next[0];
        if (target == null || compare(target.key, key) != 0) {
            return false;
        }

        for (int lane = 0; lane < target.next.length; lane++) {
            predecessors[lane].next[lane] = target.next[lane];
        }

        // Give back the empty top lanes, so later searches do not start above the
        // tallest node that is actually left.
        while (height > 1 && head.next[height - 1] == null) {
            height--;
        }

        size--;
        sizeInBytes -= key.length + target.value.length + ENTRY_OVERHEAD_BYTES;
        return true;
    }

    /**
     * Walks lane 0 from the first key to the last, which is every entry in
     * ascending order. This walk is what a flush writes to disk: one sweep, no
     * sorting step.
     *
     * Implementing Iterable means callers write an ordinary for-each loop, and
     * milestone 1.8 needs a real Iterator anyway to merge several of these.
     */
    @Override
    public Iterator<Entry> iterator() {
        return new Iterator<>() {
            private Node next = head.next[0];

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public Entry next() {
                if (next == null) {
                    throw new NoSuchElementException();
                }
                Entry entry = new Entry(next.key, next.value);
                next = next.next[0];
                return entry;
            }
        };
    }
}
