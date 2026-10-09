package io.github.shabbirflow.ironclad.wal;

import io.github.shabbirflow.ironclad.storage.SkipList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * IN ONE LINE: writes a real log to a real file, damages it in the ways a crash
 * would, and checks what recovery rebuilds.
 *
 * @TempDir gives each test its own directory, which JUnit deletes afterwards, so
 * the tests touch the disk without leaving anything behind or interfering with
 * each other.
 */
class WalFileTest {

    @TempDir
    Path directory;

    private Path log() {
        return directory.resolve("ironclad.wal");
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String s(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static List<String> contentsOf(SkipList memtable) {
        List<String> entries = new ArrayList<>();
        for (SkipList.Entry entry : memtable) {
            entries.add(s(entry.key()) + "=" + s(entry.value()));
        }
        return entries;
    }

    /** Catches: nothing at all working. Write three records, get three back. */
    @Test
    void recordsSurviveAWriterCloseAndReplay() throws IOException {
        try (WalWriter writer = WalWriter.open(log())) {
            writer.append(new WalRecord.Put(b("b"), b("2")));
            writer.append(new WalRecord.Put(b("a"), b("1")));
            writer.append(new WalRecord.Put(b("c"), b("3")));
        }

        SkipList memtable = new SkipList();
        RecoveryResult result = WalReader.replay(log(), memtable);

        assertThat(result.recordsApplied()).isEqualTo(3);
        assertThat(result.stopReason()).isEqualTo(RecoveryResult.StopReason.CLEAN_END);
        assertThat(result.validBytes()).isEqualTo(Files.size(log()));
        assertThat(contentsOf(memtable)).containsExactly("a=1", "b=2", "c=3");
    }

    /** Catches: replaying a delete as a put, which would resurrect the key. */
    @Test
    void deletesAreReplayedAsDeletes() throws IOException {
        try (WalWriter writer = WalWriter.open(log())) {
            writer.append(new WalRecord.Put(b("a"), b("1")));
            writer.append(new WalRecord.Put(b("b"), b("2")));
            writer.append(new WalRecord.Delete(b("a")));
        }

        SkipList memtable = new SkipList();
        WalReader.replay(log(), memtable);

        assertThat(memtable.get(b("a"))).isNull();
        assertThat(s(memtable.get(b("b")))).isEqualTo("2");
    }

    /** Catches: replaying out of order. The last write of a key must win. */
    @Test
    void laterWritesOfAKeyWin() throws IOException {
        try (WalWriter writer = WalWriter.open(log())) {
            writer.append(new WalRecord.Put(b("k"), b("first")));
            writer.append(new WalRecord.Put(b("k"), b("second")));
            writer.append(new WalRecord.Put(b("k"), b("third")));
        }

        SkipList memtable = new SkipList();
        WalReader.replay(log(), memtable);

        assertThat(s(memtable.get(b("k")))).isEqualTo("third");
    }

    /** Catches: a first start exploding instead of beginning empty. */
    @Test
    void replayingAMissingLogIsNotAnError() throws IOException {
        RecoveryResult result = WalReader.replay(log(), new SkipList());

        assertThat(result.stopReason()).isEqualTo(RecoveryResult.StopReason.NO_LOG);
        assertThat(result.recordsApplied()).isZero();
        assertThat(result.isClean()).isTrue();
    }

    /**
     * Catches: the crash case this whole milestone exists for. A record that was
     * half written must be dropped, everything before it kept, and the file left
     * ending on a record boundary so the next append is clean.
     */
    @Test
    void aHalfWrittenRecordAtTheEndIsDroppedAndTruncated() throws IOException {
        try (WalWriter writer = WalWriter.open(log())) {
            writer.append(new WalRecord.Put(b("a"), b("1")));
            writer.append(new WalRecord.Put(b("b"), b("2")));
        }
        long goodBytes = Files.size(log());

        // The power dies five bytes into the third record.
        ByteBuffer third = WalCodec.encode(new WalRecord.Put(b("c"), b("3")));
        appendRaw(third.slice(0, 5));

        SkipList memtable = new SkipList();
        RecoveryResult result = WalReader.replay(log(), memtable);

        assertThat(result.recordsApplied()).isEqualTo(2);
        assertThat(result.stopReason()).isEqualTo(RecoveryResult.StopReason.TORN_TAIL);
        assertThat(contentsOf(memtable)).containsExactly("a=1", "b=2");
        assertThat(Files.size(log())).isEqualTo(goodBytes);      // the stub is gone
    }

    /**
     * Catches: honouring records after a corrupt one. The third record here is
     * perfectly intact, and applying it would build a state that never existed,
     * because the operation before it is unreadable.
     */
    @Test
    void recordsAfterACorruptOneAreDiscardedEvenThoughTheyDecode() throws IOException {
        try (WalWriter writer = WalWriter.open(log())) {
            writer.append(new WalRecord.Put(b("a"), b("1")));
            writer.append(new WalRecord.Put(b("b"), b("2")));
            writer.append(new WalRecord.Put(b("c"), b("3")));
        }
        long firstRecordBytes = WalCodec.encode(new WalRecord.Put(b("a"), b("1"))).remaining();

        flipByte(firstRecordBytes + WalCodec.HEADER_BYTES + 1);   // inside record two

        SkipList memtable = new SkipList();
        RecoveryResult result = WalReader.replay(log(), memtable);

        assertThat(result.recordsApplied()).isEqualTo(1);
        assertThat(result.stopReason()).isEqualTo(RecoveryResult.StopReason.CORRUPT);
        assertThat(result.detail()).contains("checksum");
        assertThat(contentsOf(memtable)).containsExactly("a=1");
        assertThat(Files.size(log())).isEqualTo(firstRecordBytes);
    }

    /**
     * Catches: the streaming bug. With a chunk this small, records land across
     * boundaries constantly, and a reader that mistook "the chunk ran out" for
     * "the log is torn" would recover almost nothing.
     */
    @Test
    void recordsSpanningChunkBoundariesAreStillRead() throws IOException {
        try (WalWriter writer = WalWriter.open(log())) {
            for (int i = 0; i < 200; i++) {
                writer.append(new WalRecord.Put(b("key" + i), b("value-" + i)));
            }
        }

        SkipList memtable = new SkipList();
        RecoveryResult result = WalReader.replay(log(), memtable, 17);   // 17 bytes per chunk

        assertThat(result.recordsApplied()).isEqualTo(200);
        assertThat(result.stopReason()).isEqualTo(RecoveryResult.StopReason.CLEAN_END);
        assertThat(memtable.size()).isEqualTo(200);
        assertThat(s(memtable.get(b("key199")))).isEqualTo("value-199");
    }

    /** Catches: a record larger than the chunk looping forever instead of growing the buffer. */
    @Test
    void aRecordLargerThanTheChunkIsRead() throws IOException {
        byte[] big = new byte[5_000];
        java.util.Arrays.fill(big, (byte) 'x');

        try (WalWriter writer = WalWriter.open(log())) {
            writer.append(new WalRecord.Put(b("small"), b("1")));
            writer.append(new WalRecord.Put(b("big"), big));
            writer.append(new WalRecord.Put(b("after"), b("2")));
        }

        SkipList memtable = new SkipList();
        RecoveryResult result = WalReader.replay(log(), memtable, 64);   // far smaller than the record

        assertThat(result.recordsApplied()).isEqualTo(3);
        assertThat(memtable.get(b("big"))).hasSize(5_000);
        assertThat(s(memtable.get(b("after")))).isEqualTo("2");
    }

    /** Catches: a second replay of an already-recovered log changing anything. */
    @Test
    void replayIsRepeatable() throws IOException {
        try (WalWriter writer = WalWriter.open(log())) {
            writer.append(new WalRecord.Put(b("a"), b("1")));
            writer.append(new WalRecord.Delete(b("a")));
            writer.append(new WalRecord.Put(b("b"), b("2")));
        }

        RecoveryResult first = WalReader.replay(log(), new SkipList());
        RecoveryResult second = WalReader.replay(log(), new SkipList());

        assertThat(second).isEqualTo(first);
    }

    /** Catches: writes after recovery landing anywhere other than the clean boundary. */
    @Test
    void appendingAfterRecoveryContinuesTheLog() throws IOException {
        try (WalWriter writer = WalWriter.open(log())) {
            writer.append(new WalRecord.Put(b("a"), b("1")));
        }
        appendRaw(WalCodec.encode(new WalRecord.Put(b("torn"), b("x"))).slice(0, 6));

        WalReader.replay(log(), new SkipList());        // drops the torn tail

        try (WalWriter writer = WalWriter.open(log())) {
            writer.append(new WalRecord.Put(b("b"), b("2")));
        }

        SkipList memtable = new SkipList();
        RecoveryResult result = WalReader.replay(log(), memtable);

        assertThat(result.stopReason()).isEqualTo(RecoveryResult.StopReason.CLEAN_END);
        assertThat(contentsOf(memtable)).containsExactly("a=1", "b=2");
    }

    /**
     * Catches: carrying on after a durability failure. Once force() has failed we
     * do not know whether those bytes landed, so every later append must refuse
     * rather than build on an unknown prefix.
     */
    @Test
    void theWriterRefusesEverythingAfterADurabilityFailure() throws IOException {
        FileChannel channel = FileChannel.open(log(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        WalWriter writer = new WalWriter(channel);
        writer.append(new WalRecord.Put(b("a"), b("1")));

        channel.close();        // stands in for the disk going away

        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> writer.append(new WalRecord.Put(b("b"), b("2"))));
        assertThat(writer.isStopped()).isTrue();

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> writer.append(new WalRecord.Put(b("c"), b("3"))))
                .withMessageContaining("durability failure");
    }

    /** Catches: a 10,000-record log not round-tripping, with the real chunk size. */
    @Test
    void tenThousandRecordsRoundTrip() throws IOException {
        try (WalWriter writer = WalWriter.open(log())) {
            for (int i = 0; i < 10_000; i++) {
                writer.append(new WalRecord.Put(b("k" + i), b("v" + i)));
            }
        }

        SkipList memtable = new SkipList();
        RecoveryResult result = WalReader.replay(log(), memtable);

        assertThat(result.recordsApplied()).isEqualTo(10_000);
        assertThat(result.isClean()).isTrue();
        assertThat(memtable.size()).isEqualTo(10_000);
        assertThat(s(memtable.get(b("k9999")))).isEqualTo("v9999");
    }

    // ---- helpers that damage the file the way a crash would ----

    private void appendRaw(ByteBuffer bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(log(),
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
        }
    }

    private void flipByte(long index) throws IOException {
        try (FileChannel channel = FileChannel.open(log(),
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer one = ByteBuffer.allocate(1);
            channel.read(one, index);
            one.put(0, (byte) (one.get(0) ^ 0x01));
            channel.write(one.flip(), index);
        }
    }
}
