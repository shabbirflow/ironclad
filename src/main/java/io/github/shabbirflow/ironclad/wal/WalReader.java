package io.github.shabbirflow.ironclad.wal;

import io.github.shabbirflow.ironclad.storage.SkipList;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * IN ONE LINE: replays the log into the memtable, a chunk at a time, and
 * truncates whatever a crash left behind.
 *
 * Invariant: the memtable ends up in exactly the state it had when the last
 * durable record was written, and the file is left holding only that prefix.
 *
 * Streaming rather than slurping: memory is one chunk plus one record whatever
 * the log's size, records are applied as they are read so there is no temporary
 * list, and the same code works when segments arrive in milestone 1.3.
 */
public final class WalReader {

    /** One megabyte at a time. Big enough that most records never straddle a boundary. */
    public static final int DEFAULT_CHUNK_BYTES = 1024 * 1024;

    private WalReader() {
    }

    public static RecoveryResult replay(Path path, SkipList memtable) throws IOException {
        return replay(path, memtable, DEFAULT_CHUNK_BYTES);
    }

    /**
     * chunkBytes is a parameter purely so tests can set it to something tiny and
     * force records to land across a boundary. That path is the one with the
     * subtle bug in it, so it needs to be the easy path to test.
     */
    public static RecoveryResult replay(Path path, SkipList memtable, int chunkBytes) throws IOException {
        if (!Files.exists(path)) {
            return new RecoveryResult(0, 0, RecoveryResult.StopReason.NO_LOG, "no log file");
        }

        // READ to scan it, WRITE because a torn tail has to be truncated.
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {

            ByteBuffer buffer = ByteBuffer.allocate(chunkBytes);
            long validBytes = 0;
            int applied = 0;
            boolean endOfFile = false;
            RecoveryResult.StopReason stop = RecoveryResult.StopReason.CLEAN_END;
            String detail = "log ended on a record boundary";

            scan:
            while (true) {
                // ---- fill: read until this chunk is full or the file runs out ----
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) {
                        endOfFile = true;
                        break;
                    }
                }
                buffer.flip();      // stop filling, start reading: limit = what we have

                // ---- drain: take every whole record this chunk holds ----
                while (true) {
                    DecodeResult result = WalCodec.decode(buffer);

                    if (result instanceof DecodeResult.Ok ok) {
                        apply(ok.record(), memtable);
                        applied++;
                        validBytes += ok.bytesConsumed();
                        // decode deliberately left the position alone, so the
                        // cursor only moves once the record is safely applied.
                        buffer.position(buffer.position() + ok.bytesConsumed());
                        continue;
                    }

                    if (result instanceof DecodeResult.Corrupt corrupt) {
                        stop = RecoveryResult.StopReason.CORRUPT;
                        detail = corrupt.reason();
                        break scan;
                    }

                    // Incomplete, which means one of two different things.
                    // Bugs live here: treat "this chunk ran out" as "the log is
                    // torn" and recovery throws away a perfectly good log at the
                    // first chunk boundary. Only the file being exhausted makes it
                    // a torn tail.
                    if (endOfFile) {
                        if (buffer.hasRemaining()) {
                            stop = RecoveryResult.StopReason.TORN_TAIL;
                            detail = buffer.remaining() + " bytes of a half-written record at the end";
                        }
                        break scan;
                    }
                    break;      // go and read more of the file
                }

                // ---- carry the leftover forward ----
                // compact() slides the unread bytes (the start of a record whose
                // tail is in the next chunk) to position 0, and leaves the cursor
                // just after them so the next read appends to it.
                buffer.compact();

                if (!buffer.hasRemaining()) {
                    // The leftover filled the whole buffer: this record is bigger
                    // than a chunk, which is legal up to 16 MB.
                    buffer = grow(buffer);
                }
            }

            // Anything past the last good record is unreachable: either a torn
            // tail nobody was promised, or bytes after corruption that must not be
            // applied. Truncating also means the next append starts on a clean
            // boundary instead of every future recovery dying at the same byte.
            if (channel.size() > validBytes) {
                channel.truncate(validBytes);
                channel.force(true);    // true: the file's length is metadata
            }

            return new RecoveryResult(applied, validBytes, stop, detail);
        }
    }

    private static void apply(WalRecord record, SkipList memtable) {
        switch (record) {
            case WalRecord.Put put -> memtable.put(put.key(), put.value());
            case WalRecord.Delete delete -> memtable.remove(delete.key());
        }
    }

    /** Doubles the buffer, capped at the largest record the codec will accept. */
    private static ByteBuffer grow(ByteBuffer full) {
        int ceiling = WalCodec.HEADER_BYTES + WalCodec.MAX_RECORD_BYTES;

        // Unreachable while decode rejects any length above MAX_RECORD_BYTES: no
        // legal record can need more room than this. It is here so that raising
        // the limit later fails loudly instead of spinning in the caller's loop.
        if (full.capacity() >= ceiling) {
            throw new IllegalStateException("a record claims to be larger than the format allows");
        }

        ByteBuffer bigger = ByteBuffer.allocate(Math.min(ceiling, full.capacity() * 2));
        bigger.put(full.flip());    // flip: read what compact left, then copy it over
        return bigger;
    }
}
