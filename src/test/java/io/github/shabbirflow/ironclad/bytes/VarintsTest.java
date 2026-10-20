package io.github.shabbirflow.ironclad.bytes;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IN ONE LINE: pins the varint byte order and its boundaries, because round-trip
 * tests pass whichever direction the groups go.
 */
class VarintsTest {

    /**
     * Catches: the byte order silently changing. encode and decode agree with
     * each other either way round, so without exact bytes a refactor could flip
     * the order and make every existing file unreadable with a green build.
     */
    @Test
    void varintsPutTheHighGroupFirst() {
        ByteBuffer buffer = ByteBuffer.allocate(8);

        Varints.put(buffer, 300);

        assertThat(Arrays.copyOf(buffer.array(), buffer.position()))
                .containsExactly((byte) 0x82, (byte) 0x2C);     // 2 then 44, not 44 then 2
        assertThat(Varints.get(buffer.flip())).isEqualTo(300);
    }

    /** Catches: arithmetic that works for one byte and breaks at a boundary. */
    @Test
    void varintsRoundTripAcrossEveryBoundary() {
        for (int value : new int[] {0, 1, 127, 128, 16_383, 16_384, 69_420, 2_097_151,
                2_097_152, Integer.MAX_VALUE}) {
            ByteBuffer buffer = ByteBuffer.allocate(8);
            Varints.put(buffer, value);

            assertThat(buffer.position())
                    .describedAs("bytes used for %d", value)
                    .isEqualTo(Varints.length(value));
            assertThat(Varints.get(buffer.flip()))
                    .describedAs("round trip of %d", value)
                    .isEqualTo(value);
        }
    }

    /** Catches: a runaway loop on bytes that are not a varint at all. */
    @Test
    void aVarintWithNoTerminatingByteIsRejected() {
        ByteBuffer endless = ByteBuffer.wrap(new byte[] {
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});

        assertThat(Varints.get(endless)).isEqualTo(-1);
    }
}
