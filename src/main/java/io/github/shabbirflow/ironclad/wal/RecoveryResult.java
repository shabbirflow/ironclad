package io.github.shabbirflow.ironclad.wal;

/**
 * IN ONE LINE: what one replay of the log found, and why it stopped reading.
 *
 * The codec reports `Incomplete` without knowing whether the chunk ran out or the
 * log did, because it only ever sees the bytes in front of it. The reader knows
 * whether the file had more, so the distinction is drawn here instead.
 */
public record RecoveryResult(int recordsApplied, long validBytes, StopReason stopReason, String detail) {

    public enum StopReason {

        /** No log file at all: a first start, or the log was deleted. */
        NO_LOG,

        /** The log ended exactly on a record boundary. The normal, happy case. */
        CLEAN_END,

        /**
         * A record was half written when the power went. Everything before it is
         * intact, nothing was acknowledged after it, and the tail gets truncated.
         */
        TORN_TAIL,

        /**
         * A record's bytes were present but wrong: a failed checksum, or a length
         * that cannot be real. Everything after it is discarded too, even if it
         * decodes, because applying it would produce a state that never existed.
         */
        CORRUPT
    }

    /** True when the whole file was readable, so nothing had to be thrown away. */
    public boolean isClean() {
        return stopReason == StopReason.CLEAN_END || stopReason == StopReason.NO_LOG;
    }
}
