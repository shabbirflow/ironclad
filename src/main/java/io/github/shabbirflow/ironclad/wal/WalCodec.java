package io.github.shabbirflow.ironclad.wal;

import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/**
 * IN ONE LINE: turns one WalRecord into bytes and back, and detects bytes that
 * were damaged or never finished being written.
 *
 * Layout on disk, big-endian, because an on-disk format has to pick a byte order
 * explicitly instead of inheriting whatever the machine prefers:
 *
 *   [ crc32c : 4 ][ length : 4 ][ payload : length bytes ]
 *     the crc covers the length field AND the payload
 *
 *   payload = [ kind : 1 ][ keyLength : varint ][ key ][ valueLength : varint ][ value ]
 *             kind 1 = put, kind 2 = delete, which stops after the key
 *
 * Why a checksum and not just the length: if the power died halfway through
 * writing the payload, the length field still reads perfectly and you would
 * deserialise garbage with complete confidence. Only a checksum over the content
 * proves the content is complete. That is a torn write, and detecting it is the
 * entire basis of recovery.
 *
 * Why CRC32C rather than CRC32: a different polynomial (Castagnoli) that detects
 * more error patterns at the record sizes storage uses, and the one the industry
 * standardised on for this job (iSCSI, ext4, LevelDB). The JDK has provided it,
 * hardware-accelerated, since Java 9.
 */
public final class WalCodec {

    /** Header is crc(4) + length(4). */
    public static final int HEADER_BYTES = 8;

    /**
     * A ceiling on one record, checked before we believe the length field. A
     * corrupted length could otherwise ask us to allocate two gigabytes before we
     * are in any position to verify it.
     */
    public static final int MAX_RECORD_BYTES = 16 * 1024 * 1024;

    private static final byte KIND_PUT = 1;
    private static final byte KIND_DELETE = 2;

    private WalCodec() {
    }

    /** The complete on-disk record, header included, ready to append. */
    public static ByteBuffer encode(WalRecord record) {
        byte[] key = record.key();
        byte[] value = record instanceof WalRecord.Put put ? put.value() : null;

        int payloadBytes = 1 + varintBytes(key.length) + key.length
                + (value == null ? 0 : varintBytes(value.length) + value.length);

        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + payloadBytes);
        buffer.position(HEADER_BYTES);                      // leave room for the header
        buffer.put(value == null ? KIND_DELETE : KIND_PUT);
        putVarint(buffer, key.length);
        buffer.put(key);
        if (value != null) {
            putVarint(buffer, value.length);
            buffer.put(value);
        }

        // Absolute puts, so filling in the header does not disturb the position
        // the payload was written with.
        buffer.putInt(4, payloadBytes);
        CRC32C crc = new CRC32C();
        crc.update(buffer.array(), 4, 4 + payloadBytes);    // length field included
        buffer.putInt(0, (int) crc.getValue());

        return buffer.position(0).limit(HEADER_BYTES + payloadBytes);
    }

    /**
     * Reads one record starting at the buffer's position, without moving it. The
     * caller advances by bytesConsumed, and only when the result is Ok.
     */
    public static DecodeResult decode(ByteBuffer source) {
        int start = source.position();
        int available = source.limit() - start;
        if (available < HEADER_BYTES) {
            return new DecodeResult.Incomplete();
        }

        int expectedCrc = source.getInt(start);
        int length = source.getInt(start + 4);

        if (length < 0 || length > MAX_RECORD_BYTES) {
            return new DecodeResult.Corrupt("length " + length + " is not a real record size");
        }
        if (available < HEADER_BYTES + length) {
            return new DecodeResult.Incomplete();
        }

        CRC32C crc = new CRC32C();
        crc.update(source.slice(start + 4, 4 + length));
        if ((int) crc.getValue() != expectedCrc) {
            return new DecodeResult.Corrupt("checksum mismatch");
        }

        // Past this point the bytes are proven intact, so a parse failure means
        // our own format is wrong rather than the disk having lied.
        ByteBuffer payload = source.slice(start + HEADER_BYTES, length);
        byte kind = payload.get();
        int keyLength = getVarint(payload);
        if (keyLength < 0 || keyLength > payload.remaining()) {
            return new DecodeResult.Corrupt("key length " + keyLength + " does not fit the record");
        }
        byte[] key = new byte[keyLength];
        payload.get(key);

        return switch (kind) {
            case KIND_DELETE -> new DecodeResult.Ok(
                    new WalRecord.Delete(key), HEADER_BYTES + length);
            case KIND_PUT -> {
                int valueLength = getVarint(payload);
                if (valueLength < 0 || valueLength > payload.remaining()) {
                    yield new DecodeResult.Corrupt(
                            "value length " + valueLength + " does not fit the record");
                }
                byte[] value = new byte[valueLength];
                payload.get(value);
                yield new DecodeResult.Ok(new WalRecord.Put(key, value), HEADER_BYTES + length);
            }
            default -> new DecodeResult.Corrupt("unknown record kind " + kind);
        };
    }

    // ---- varints: seven bits of number per byte, top bit means "another follows" ----
    //
    // Lengths are usually small. A 20-byte key spends one byte on its length this
    // way and four bytes with a fixed int, and the WAL is the file we write most.

    static int varintBytes(int value) {
        int bytes = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            bytes++;
        }
        return bytes;
    }

    static void putVarint(ByteBuffer buffer, int value) {
        while ((value & ~0x7F) != 0) {
            buffer.put((byte) ((value & 0x7F) | 0x80));      // seven bits, continuation set
            value >>>= 7;
        }
        buffer.put((byte) value);
    }

    /** Returns -1 when the encoding never terminates within five bytes. */
    static int getVarint(ByteBuffer buffer) {
        int result = 0;
        for (int shift = 0; shift <= 28; shift += 7) {
            if (!buffer.hasRemaining()) {
                return -1;
            }
            byte b = buffer.get();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        return -1;
    }
}
