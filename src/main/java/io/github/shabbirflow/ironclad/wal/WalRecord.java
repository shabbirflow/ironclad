package io.github.shabbirflow.ironclad.wal;

/**
 * IN ONE LINE: one thing that happened, as the log remembers it.
 *
 * Sealed, so the codec's switch over the two kinds is checked by the compiler.
 * Delete exists because replaying a log that only knew about puts would
 * resurrect every deleted key.
 */
public sealed interface WalRecord {

    byte[] key();

    record Put(byte[] key, byte[] value) implements WalRecord {}

    record Delete(byte[] key) implements WalRecord {}
}
