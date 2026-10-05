package io.github.shabbirflow.ironclad.maelstrom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * IN ONE LINE: the first benchmark, here to prove the JMH plumbing works before
 * milestone 1.1 needs real numbers.
 *
 * It measures what it costs to parse one Maelstrom message, which is also the
 * per-message overhead of the protocol itself.
 *
 * Iteration counts are deliberately small: this is a smoke test, not a result.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
public class MessageParseBenchmark {

    private static final String ECHO_LINE =
            "{\"src\":\"c1\",\"dest\":\"n1\",\"body\":{\"type\":\"echo\",\"msg_id\":3,"
                    + "\"echo\":\"Please echo 35\"}}";

    private ObjectMapper mapper;

    /** Runs once, outside the timed section, so setup cost is not measured. */
    @Setup
    public void setUp() {
        mapper = MaelstromClient.newMapper();
    }

    /**
     * Blackhole.consume exists because the JIT deletes work whose result is
     * never used. Without it this method could be optimised down to nothing and
     * report a gloriously fake number.
     */
    @Benchmark
    public void parseEchoMessage(Blackhole hole) throws Exception {
        hole.consume(mapper.readValue(ECHO_LINE, Message.class));
    }
}
