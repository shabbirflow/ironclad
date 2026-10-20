package io.github.shabbirflow.ironclad.bytes;

import java.nio.ByteBuffer;

/**
 * IN ONE LINE: variable-length integers, shared by every on-disk format here.
 *
 * Moved out of WalCodec because the SSTable format uses the same encoding. Two
 * copies of a byte format drift, and the day they drift is the day a file
 * written by one half of the engine is unreadable by the other.
 *
 * High group first, so a varint reads left to right like the big-endian fixed
 * fields around it.
 */
public final class Varints {

    private Varints() {
    }

    /**
     * Counts how many bytes the varint form of this number will take: 1 byte for
     * 0 to 127, 2 for 128 to 16383, 3 up to about 2 million. It counts and writes
     * nothing, because buffers have to be sized before anything goes into them,
     * and because high-group-first cannot start writing until the count is known.
     */
    public static int length(int value) {
        int bytes = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            bytes++;
        }
        return bytes;
    }

    /**
     * Writes the number highest seven bits first, with the top bit of each byte
     * set to mean "another byte follows" and clear on the last one.
     *
     * 300 becomes 0x82 0x2C: group 2 carrying the continuation flag, then group
     * 44. Read left to right that is (2 << 7) + 44 = 300.
     */
    public static void put(ByteBuffer buffer, int value) {
        int groups = length(value);
        for (int group = groups - 1; group >= 0; group--) {
            int sevenBits = (value >>> (7 * group)) & 0x7F;
            buffer.put((byte) (group == 0 ? sevenBits : sevenBits | 0x80));
        }
    }

    /**
     * Reads one back: shift what we have seven bits left, drop the next group
     * into the gap, stop at the first byte whose top bit is clear. No shift
     * counter, because the groups arrive most significant first.
     *
     * Returns -1 when these bytes are not a varint: no terminating byte within
     * five (an int is 32 bits and 5 x 7 = 35), or a value too large for an int.
     * Accumulating in a long is what makes that second check possible, since the
     * overflow would otherwise be silent.
     */
    public static int get(ByteBuffer buffer) {
        long result = 0;
        for (int bytesRead = 0; bytesRead < 5; bytesRead++) {
            if (!buffer.hasRemaining()) {
                return -1;
            }
            byte b = buffer.get();
            result = (result << 7) | (b & 0x7F);
            if (result > Integer.MAX_VALUE) {
                return -1;
            }
            if ((b & 0x80) == 0) {
                return (int) result;
            }
        }
        return -1;
    }
}
