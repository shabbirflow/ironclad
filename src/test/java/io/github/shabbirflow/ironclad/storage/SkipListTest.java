package io.github.shabbirflow.ironclad.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * IN ONE LINE: proves the skip list behaves exactly like a TreeMap, including
 * for randomly generated operation sequences.
 *
 * TreeMap is the oracle, not the rival: it is a known-correct sorted map, so any
 * disagreement is a bug in our structure. Performance comparison is a separate
 * job, done with JMH against ConcurrentSkipListMap.
 */
class SkipListTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String s(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Catches: a stored key that cannot be found again. The simplest thing that could break. */
    @Test
    void storesAndFindsAValue() {
        SkipList list = new SkipList();
        list.put(b("k"), b("v"));

        assertThat(s(list.get(b("k")))).isEqualTo("v");
        assertThat(list.size()).isEqualTo(1);
    }

    /** Catches: a missing key returning something, or throwing. */
    @Test
    void missingKeyIsNull() {
        SkipList list = new SkipList();
        list.put(b("a"), b("1"));

        assertThat(list.get(b("zzz"))).isNull();
        assertThat(list.get(b(""))).isNull();
    }

    /** Catches: a second put inserting a duplicate node instead of replacing the value. */
    @Test
    void secondPutOverwritesTheValueAndDoesNotGrow() {
        SkipList list = new SkipList();
        list.put(b("k"), b("old"));
        list.put(b("k"), b("new"));

        assertThat(s(list.get(b("k")))).isEqualTo("new");
        assertThat(list.size()).isEqualTo(1);
    }

    /** Catches: iteration in insertion order instead of key order, which would break every flush. */
    @Test
    void iteratesInKeyOrderWhateverTheInsertionOrder() {
        SkipList list = new SkipList();
        for (String key : List.of("queen", "ace", "jack", "king", "ten")) {
            list.put(b(key), b(key.substring(0, 1)));
        }

        List<String> keys = new ArrayList<>();
        list.forEach(entry -> keys.add(s(entry.key())));

        assertThat(keys).containsExactly("ace", "jack", "king", "queen", "ten");
    }

    /**
     * Catches: signed byte comparison. Java's byte is signed, so 0x80 is -128 and
     * a naive compare sorts it before 0x01. On disk, keys must be in unsigned byte
     * order, and getting this wrong corrupts every SSTable we ever write.
     */
    @Test
    void ordersBytesAsUnsignedValues() {
        SkipList list = new SkipList();
        byte[] low = {0x01};
        byte[] high = {(byte) 0x80};      // 128 unsigned, -128 signed
        list.put(high, b("high"));
        list.put(low, b("low"));

        List<byte[]> keys = new ArrayList<>();
        list.forEach(entry -> keys.add(entry.key()));

        assertThat(keys.get(0)).isEqualTo(low);
        assertThat(keys.get(1)).isEqualTo(high);
    }

    /** Catches: byte accounting drifting, which would make flush timing a lottery. */
    @Test
    void tracksBytesOfKeysAndValues() {
        SkipList list = new SkipList();
        long empty = list.sizeInBytes();

        list.put(b("key"), b("value"));          // 3 + 5 bytes of payload

        assertThat(list.sizeInBytes()).isEqualTo(empty + 8 + SkipList.ENTRY_OVERHEAD_BYTES);
    }

    /**
     * Catches: anything the small tests miss. Thousands of random puts and gets
     * against the oracle, with a fixed seed so a failure is reproducible.
     */
    @Test
    void agreesWithTreeMapOnRandomOperations() {
        Random random = new Random(20261005L);
        SkipList list = new SkipList(new Random(7L));
        TreeMap<String, String> oracle = new TreeMap<>();

        for (int i = 0; i < 20_000; i++) {
            String key = "k" + random.nextInt(500);
            if (random.nextInt(3) == 0) {
                byte[] actual = list.get(b(key));
                String expected = oracle.get(key);
                assertThat(actual == null ? null : s(actual)).isEqualTo(expected);
            } else {
                String value = "v" + i;
                list.put(b(key), b(value));
                oracle.put(key, value);
            }
        }

        assertThat(list.size()).isEqualTo(oracle.size());

        List<String> ours = new ArrayList<>();
        list.forEach(entry -> ours.add(s(entry.key()) + "=" + s(entry.value())));
        List<String> theirs = oracle.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .toList();

        assertThat(ours).isEqualTo(theirs);
    }

    /** Catches: keys sharing a prefix colliding, or a wrong length comparison. */
    @Test
    void shorterKeySortsBeforeItsOwnPrefixExtension() {
        SkipList list = new SkipList();
        for (String key : List.of("user", "user:1", "user:10", "user:2", "us")) {
            list.put(b(key), b("x"));
        }

        List<String> keys = new ArrayList<>();
        list.forEach(entry -> keys.add(s(entry.key())));

        assertThat(keys).containsExactly("us", "user", "user:1", "user:10", "user:2");
    }

    /** Sanity check on the oracle comparison itself: both agree on byte ordering. */
    @Test
    void matchesTreeMapOrderingForByteArrayKeys() {
        TreeMap<byte[], String> oracle = new TreeMap<>(Arrays::compareUnsigned);
        SkipList list = new SkipList();
        for (int i = 0; i < 200; i++) {
            byte[] key = {(byte) (i * 37 % 256)};
            oracle.put(key, "v" + i);
            list.put(key, b("v" + i));
        }

        List<String> ours = new ArrayList<>();
        list.forEach(entry -> ours.add(s(entry.key())));
        List<String> theirs = oracle.keySet().stream().map(SkipListTest::s).toList();

        assertThat(ours).isEqualTo(theirs);
        assertThat(oracle.entrySet().stream().map(Map.Entry::getValue).toList()).hasSize(oracle.size());
    }

    /** Catches: a plain for-each loop missing entries, or visiting them out of order. */
    @Test
    void worksInAPlainForEachLoop() {
        SkipList list = new SkipList();
        for (String key : List.of("delta", "alpha", "charlie", "bravo")) {
            list.put(b(key), b(key.toUpperCase()));
        }

        List<String> seen = new ArrayList<>();
        for (SkipList.Entry entry : list) {          // no lambda in sight
            seen.add(s(entry.key()) + "=" + s(entry.value()));
        }

        assertThat(seen).containsExactly("alpha=ALPHA", "bravo=BRAVO", "charlie=CHARLIE", "delta=DELTA");
    }

    /** Catches: an iterator that runs off the end quietly instead of saying so. */
    @Test
    void iteratorOnAnEmptyListHasNothing() {
        SkipList list = new SkipList();

        assertThat(list.iterator().hasNext()).isFalse();
        assertThatExceptionOfType(NoSuchElementException.class)
                .isThrownBy(() -> list.iterator().next());
    }

    /** Catches: a delete that leaves the key findable, or breaks the lane-0 chain. */
    @Test
    void removeUnlinksTheKey() {
        SkipList list = new SkipList();
        for (String key : List.of("a", "b", "c")) {
            list.put(b(key), b(key));
        }

        assertThat(list.remove(b("b"))).isTrue();
        assertThat(list.get(b("b"))).isNull();
        assertThat(list.size()).isEqualTo(2);

        List<String> keys = new ArrayList<>();
        list.forEach(entry -> keys.add(s(entry.key())));
        assertThat(keys).containsExactly("a", "c");
    }

    /** Catches: removing something absent reporting success, or corrupting the list. */
    @Test
    void removingAMissingKeyChangesNothing() {
        SkipList list = new SkipList();
        list.put(b("a"), b("1"));
        long bytesBefore = list.sizeInBytes();

        assertThat(list.remove(b("nope"))).isFalse();
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.sizeInBytes()).isEqualTo(bytesBefore);
    }

    /** Catches: a removed node's tower left dangling, so the key cannot be re-inserted. */
    @Test
    void keyCanBeReinsertedAfterRemoval() {
        SkipList list = new SkipList(new Random(99L));
        for (int i = 0; i < 200; i++) {
            list.put(b("k" + i), b("v" + i));
        }

        for (int i = 0; i < 200; i += 2) {
            assertThat(list.remove(b("k" + i))).isTrue();
        }
        for (int i = 0; i < 200; i += 2) {
            list.put(b("k" + i), b("again" + i));
        }

        assertThat(list.size()).isEqualTo(200);
        assertThat(s(list.get(b("k0")))).isEqualTo("again0");
        assertThat(s(list.get(b("k1")))).isEqualTo("v1");
    }

    /** Catches: bytes not returned on removal, so the memtable would flush too early forever. */
    @Test
    void removeGivesBackTheBytes() {
        SkipList list = new SkipList();
        long empty = list.sizeInBytes();
        list.put(b("key"), b("value"));
        list.remove(b("key"));

        assertThat(list.sizeInBytes()).isEqualTo(empty);
    }

    /**
     * Catches: anything the small tests miss, now with deletes in the mix. The
     * seeds make any failure replayable.
     */
    @Test
    void agreesWithTreeMapOnRandomPutsGetsAndRemoves() {
        Random ops = new Random(31337L);
        SkipList list = new SkipList(new Random(11L));
        TreeMap<String, String> oracle = new TreeMap<>();

        for (int i = 0; i < 30_000; i++) {
            String key = "k" + ops.nextInt(300);
            switch (ops.nextInt(4)) {
                case 0 -> {
                    byte[] actual = list.get(b(key));
                    assertThat(actual == null ? null : s(actual)).isEqualTo(oracle.get(key));
                }
                case 1 -> assertThat(list.remove(b(key))).isEqualTo(oracle.remove(key) != null);
                default -> {
                    String value = "v" + i;
                    list.put(b(key), b(value));
                    oracle.put(key, value);
                }
            }
        }

        assertThat(list.size()).isEqualTo(oracle.size());

        List<String> ours = new ArrayList<>();
        list.forEach(entry -> ours.add(s(entry.key()) + "=" + s(entry.value())));
        assertThat(ours).isEqualTo(oracle.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .toList());
    }
}
