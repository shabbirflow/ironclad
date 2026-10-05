package io.github.shabbirflow.ironclad.storage;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;

/**
 * IN ONE LINE: our hand-written skip list against the JDK's ConcurrentSkipListMap,
 * on one thread.
 *
 * Read the result honestly. One thread is the case where the JDK's structure can
 * only lose: it pays for atomic pointer updates that nobody is competing for.
 * Beating it here measures the price of thread safety, not a better design.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
public class SkipListBenchmark {

    /** A power of two, so the probe counter can wrap with a mask. */
    private static final int KEYS = 8192;

    private byte[][] keys;
    private byte[][] absentKeys;
    private byte[] value;
    private SkipList ours;
    private ConcurrentSkipListMap<byte[], byte[]> jdk;
    private int probe;

    @Setup(Level.Trial)
    public void setUp() {
        Random random = new Random(42L);
        keys = new byte[KEYS][];
        absentKeys = new byte[KEYS][];
        for (int i = 0; i < KEYS; i++) {
            keys[i] = new byte[16];
            absentKeys[i] = new byte[16];
            random.nextBytes(keys[i]);
            random.nextBytes(absentKeys[i]);
        }
        value = new byte[100];
        random.nextBytes(value);

        ours = new SkipList(new Random(1L));
        jdk = new ConcurrentSkipListMap<>(Arrays::compareUnsigned);
        for (byte[] key : keys) {
            ours.put(key, value);
            jdk.put(key, value);
        }

        buildFlat();
    }

    // ---- insert: a fresh structure each invocation, so both pay construction ----

    @Benchmark
    @OperationsPerInvocation(KEYS)
    public void insertOurs(Blackhole hole) {
        SkipList list = new SkipList(new Random(1L));
        for (byte[] key : keys) {
            list.put(key, value);
        }
        hole.consume(list);
    }

    @Benchmark
    @OperationsPerInvocation(KEYS)
    public void insertJdk(Blackhole hole) {
        ConcurrentSkipListMap<byte[], byte[]> map = new ConcurrentSkipListMap<>(Arrays::compareUnsigned);
        for (byte[] key : keys) {
            map.put(key, value);
        }
        hole.consume(map);
    }

    // ---- point lookups: returning the value is enough, JMH consumes returns ----

    @Benchmark
    public byte[] getHitOurs() {
        return ours.get(keys[probe++ & (KEYS - 1)]);
    }

    @Benchmark
    public byte[] getHitJdk() {
        return jdk.get(keys[probe++ & (KEYS - 1)]);
    }

    @Benchmark
    public byte[] getMissOurs() {
        return ours.get(absentKeys[probe++ & (KEYS - 1)]);
    }

    @Benchmark
    public byte[] getMissJdk() {
        return jdk.get(absentKeys[probe++ & (KEYS - 1)]);
    }

    // ---- full ordered scan: this is what a flush does ----

    @Benchmark
    @OperationsPerInvocation(KEYS)
    public void scanOurs(Blackhole hole) {
        for (SkipList.Entry entry : ours) {
            hole.consume(entry.key());
        }
    }

    @Benchmark
    @OperationsPerInvocation(KEYS)
    public void scanJdk(Blackhole hole) {
        for (Map.Entry<byte[], byte[]> entry : jdk.entrySet()) {
            hole.consume(entry.getKey());
        }
    }

    // ---- these two were an experiment whose hypothesis turned out to be wrong ----
    //
    // The guess was that scanOurs lost because it allocates an Entry per step.
    // The gc profiler killed that: gc.alloc.rate.norm is around 10^-6 B/op for
    // every variant here, including the ones that "allocate". Escape analysis
    // deletes the Entry outright, because it never leaves the loop.
    //
    // So these measure call shape (visitor callback versus iterator), not
    // allocation. Kept because the comparison is still informative, and because
    // the dead hypothesis is worth remembering.

    @Benchmark
    @OperationsPerInvocation(KEYS)
    public void scanOursNoAlloc(Blackhole hole) {
        ours.visitAll((key, val) -> hole.consume(key));
    }

    @Benchmark
    @OperationsPerInvocation(KEYS)
    public void scanJdkNoAlloc(Blackhole hole) {
        for (byte[] key : jdk.keySet()) {
            hole.consume(key);
        }
    }

    /**
     * The layout control. A bare singly-linked list over the same keys, where
     * next is a direct field, so a hop is one dereference instead of two.
     *
     * Our skip list's lane-0 walk does the same logical work, but reads
     * node.next[0], which means node, then its Node[] array object, then the
     * slot: two objects pulled into cache per step instead of one. If this
     * baseline is much faster than scanOurs, that indirection is the cost.
     */
    static final class Flat {
        byte[] key;
        byte[] value;
        Flat next;
    }

    private Flat flatHead;

    /** Called from setUp: JMH does not order two @Setup methods, and this needs keys. */
    private void buildFlat() {
        byte[][] sorted = keys.clone();
        Arrays.sort(sorted, Arrays::compareUnsigned);
        Flat previous = null;
        for (byte[] key : sorted) {
            Flat node = new Flat();
            node.key = key;
            node.value = value;
            if (previous == null) {
                flatHead = node;
            } else {
                previous.next = node;
            }
            previous = node;
        }
    }

    @Benchmark
    @OperationsPerInvocation(KEYS)
    public void scanFlatBaseline(Blackhole hole) {
        for (Flat node = flatHead; node != null; node = node.next) {
            hole.consume(node.key);
        }
    }
}
