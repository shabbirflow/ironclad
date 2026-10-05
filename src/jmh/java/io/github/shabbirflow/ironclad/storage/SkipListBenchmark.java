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
}
