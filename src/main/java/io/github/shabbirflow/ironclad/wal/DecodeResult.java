package io.github.shabbirflow.ironclad.wal;

/**
 * IN ONE LINE: what reading one record produced, including the two ways it can
 * fail, which recovery has to tell apart.
 *
 * A result type rather than exceptions, because "the file ended mid-record" is
 * the normal way a crashed log ends, not an error. Recovery stops on anything
 * that is not Ok, and the reason is worth logging for a human to read later.
 */
public sealed interface DecodeResult {

    /** A complete record whose checksum matched. bytesConsumed advances the reader. */
    record Ok(WalRecord record, int bytesConsumed) implements DecodeResult {}

    /** Fewer bytes left than this record needs: the crash happened mid-write. */
    record Incomplete() implements DecodeResult {}

    /** The bytes are there but wrong: a flipped bit, or a length that cannot be real. */
    record Corrupt(String reason) implements DecodeResult {}
}
