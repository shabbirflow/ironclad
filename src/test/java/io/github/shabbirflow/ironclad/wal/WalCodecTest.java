package io.github.shabbirflow.ironclad.wal;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IN ONE LINE: proves the byte layout round-trips, and that damaged or
 * unfinished bytes are reported rather than parsed.
 *
 * All in memory. Corrupting a buffer is three lines here; corrupting a real file
 * on disk to get the same coverage would be a test you never bother to write.
 */
class WalCodecTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String s(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Catches: the simplest possible break, a record that cannot be read back. */
    @Test
    void putRoundTrips() {
        ByteBuffer encoded = WalCodec.encode(new WalRecord.Put(b("user:42"), b("shabbir")));

        DecodeResult result = WalCodec.decode(encoded);

        assertThat(result).isInstanceOfSatisfying(DecodeResult.Ok.class, ok -> {
            assertThat(ok.bytesConsumed()).isEqualTo(encoded.limit());
            assertThat(ok.record()).isInstanceOfSatisfying(WalRecord.Put.class, put -> {
                assertThat(s(put.key())).isEqualTo("user:42");
                assertThat(s(put.value())).isEqualTo("shabbir");
            });
        });
    }

    /** Catches: a delete decoded as a put with an empty value, which would resurrect keys. */
    @Test
    void deleteRoundTripsAsADelete() {
        DecodeResult result = WalCodec.decode(WalCodec.encode(new WalRecord.Delete(b("user:42"))));

        assertThat(result).isInstanceOfSatisfying(DecodeResult.Ok.class,
                ok -> assertThat(ok.record()).isInstanceOfSatisfying(WalRecord.Delete.class,
                        delete -> assertThat(s(delete.key())).isEqualTo("user:42")));
    }

    /** Catches: zero-length arrays handled as absent rather than empty. */
    @Test
    void emptyKeyAndEmptyValueSurvive() {
        DecodeResult result = WalCodec.decode(WalCodec.encode(new WalRecord.Put(new byte[0], new byte[0])));

        assertThat(result).isInstanceOfSatisfying(DecodeResult.Ok.class,
                ok -> assertThat(ok.record()).isInstanceOfSatisfying(WalRecord.Put.class, put -> {
                    assertThat(put.key()).isEmpty();
                    assertThat(put.value()).isEmpty();
                }));
    }

    /**
     * Catches: a varint written or read wrongly past one byte. 127 fits in one
     * byte, 128 is the first that needs two, and 300 needs two with both used.
     */
    @Test
    void valuesLongerThanOneVarintByteRoundTrip() {
        for (int size : new int[] {1, 127, 128, 300, 16_384}) {
            byte[] value = new byte[size];
            new Random(size).nextBytes(value);

            DecodeResult result = WalCodec.decode(WalCodec.encode(new WalRecord.Put(b("k"), value)));

            assertThat(result).isInstanceOfSatisfying(DecodeResult.Ok.class,
                    ok -> assertThat(((WalRecord.Put) ok.record()).value()).isEqualTo(value));
        }
    }

    /** Catches: the checksum not actually covering the payload. One flipped bit must be seen. */
    @Test
    void aFlippedBitInThePayloadIsDetected() {
        ByteBuffer encoded = WalCodec.encode(new WalRecord.Put(b("key"), b("value")));
        encoded.put(encoded.limit() - 1, (byte) (encoded.get(encoded.limit() - 1) ^ 0x01));

        assertThat(WalCodec.decode(encoded))
                .isInstanceOfSatisfying(DecodeResult.Corrupt.class,
                        corrupt -> assertThat(corrupt.reason()).contains("checksum"));
    }

    /**
     * Catches: the checksum covering only the payload. This is why the layout puts
     * the crc first and includes the length field in it.
     */
    @Test
    void aFlippedBitInTheLengthFieldIsDetected() {
        ByteBuffer encoded = WalCodec.encode(new WalRecord.Put(b("key"), b("value")));
        encoded.putInt(4, encoded.getInt(4) - 1);           // claim one byte fewer

        assertThat(WalCodec.decode(encoded))
                .isInstanceOfSatisfying(DecodeResult.Corrupt.class,
                        corrupt -> assertThat(corrupt.reason()).contains("checksum"));
    }

    /** Catches: a garbage length sending us off to allocate gigabytes. */
    @Test
    void anImpossibleLengthIsRejectedBeforeAnythingIsAllocated() {
        ByteBuffer encoded = WalCodec.encode(new WalRecord.Put(b("key"), b("value")));
        encoded.putInt(4, Integer.MAX_VALUE);

        assertThat(WalCodec.decode(encoded))
                .isInstanceOfSatisfying(DecodeResult.Corrupt.class,
                        corrupt -> assertThat(corrupt.reason()).contains("not a real record size"));
    }

    /**
     * Catches: treating a half-written record as corruption. This is the normal
     * way a crashed log ends, and recovery reports it differently.
     */
    @Test
    void aRecordCutShortIsIncompleteNotCorrupt() {
        ByteBuffer encoded = WalCodec.encode(new WalRecord.Put(b("key"), b("value")));

        for (int kept = 0; kept < encoded.limit(); kept++) {
            ByteBuffer truncated = encoded.slice(0, kept);

            assertThat(WalCodec.decode(truncated))
                    .describedAs("first %d bytes of the record", kept)
                    .isInstanceOf(DecodeResult.Incomplete.class);
        }
    }

    /** Catches: decode moving the position, which would desynchronise the reader. */
    @Test
    void decodeLeavesThePositionWhereItFoundIt() {
        ByteBuffer encoded = WalCodec.encode(new WalRecord.Put(b("key"), b("value")));

        WalCodec.decode(encoded);

        assertThat(encoded.position()).isZero();
    }

    /**
     * Catches: a reader that cannot walk several records in one buffer, which is
     * exactly what replay does.
     */
    @Test
    void recordsCanBeReadBackToBackFromOneBuffer() {
        ByteBuffer first = WalCodec.encode(new WalRecord.Put(b("a"), b("1")));
        ByteBuffer second = WalCodec.encode(new WalRecord.Delete(b("b")));
        ByteBuffer third = WalCodec.encode(new WalRecord.Put(b("c"), b("3")));

        ByteBuffer log = ByteBuffer.allocate(first.limit() + second.limit() + third.limit());
        log.put(first).put(second).put(third).flip();

        StringBuilder replayed = new StringBuilder();
        while (log.hasRemaining()) {
            DecodeResult result = WalCodec.decode(log);
            assertThat(result).isInstanceOf(DecodeResult.Ok.class);
            DecodeResult.Ok ok = (DecodeResult.Ok) result;
            replayed.append(s(ok.record().key()));
            log.position(log.position() + ok.bytesConsumed());
        }

        assertThat(replayed.toString()).isEqualTo("abc");
    }
}
