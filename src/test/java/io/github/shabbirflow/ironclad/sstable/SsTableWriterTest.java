package io.github.shabbirflow.ironclad.sstable;

import io.github.shabbirflow.ironclad.storage.SkipList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32C;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * IN ONE LINE: writes real files and then takes them apart byte by byte, using a
 * parser written from the format description rather than from the writer's code.
 *
 * The milestone asks for a test that shares no code with the writer, and this is
 * why: if both sides called the same helper, a bug inside it would make them
 * agree with each other and both be wrong. The parser below re-implements the
 * varint decoding independently and checks every checksum itself.
 */
class SsTableWriterTest {

    @TempDir
    Path directory;

    private Path table() {
        return directory.resolve("000001.sst");
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String s(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- tests

    /** Catches: a file that is not identifiable, or silently a different format. */
    @Test
    void theFileEndsWithTheMagicAndTheFormatVersion() throws IOException {
        try (SsTableWriter writer = SsTableWriter.create(table())) {
            writer.add(b("a"), b("1"));
            writer.finish();
        }

        byte[] bytes = Files.readAllBytes(table());
        Footer footer = Footer.of(bytes);

        assertThat(footer.magic).isEqualTo("IRONCLAD");
        assertThat(footer.version).isEqualTo(1);
        assertThat(footer.entryCount).isEqualTo(1);
    }

    /** Catches: the simplest break, one entry that cannot be found in the bytes. */
    @Test
    void oneEntryRoundTripsThroughTheRawBytes() throws IOException {
        try (SsTableWriter writer = SsTableWriter.create(table())) {
            writer.add(b("user:42"), b("shabbir"));
            writer.finish();
        }

        List<Entry> entries = new Parser(Files.readAllBytes(table())).allEntries();

        assertThat(entries).hasSize(1);
        assertThat(s(entries.get(0).key)).isEqualTo("user:42");
        assertThat(s(entries.get(0).value)).isEqualTo("shabbir");
        assertThat(entries.get(0).kind).isEqualTo(SsTableFormat.KIND_VALUE);
    }

    /** Catches: entries lost or reordered once there are enough to need many blocks. */
    @Test
    void everyEntryComesBackInKeyOrderAcrossManyBlocks() throws IOException {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            keys.add(String.format("key%05d", i));
        }

        try (SsTableWriter writer = SsTableWriter.create(table())) {
            for (String key : keys) {
                writer.add(b(key), b("value-of-" + key));
            }
            SsTableInfo info = writer.finish();
            assertThat(info.entryCount()).isEqualTo(2_000);
            assertThat(info.blockCount()).isGreaterThan(1);      // it really did split
        }

        Parser parser = new Parser(Files.readAllBytes(table()));
        List<Entry> entries = parser.allEntries();

        assertThat(entries).hasSize(2_000);
        assertThat(entries.stream().map(e -> s(e.key)).toList()).isEqualTo(keys);
        assertThat(s(entries.get(1_999).value)).isEqualTo("value-of-key01999");
    }

    /**
     * Catches: blocks drifting away from the page size they exist to match. Every
     * block holds either one entry, or entries totalling no more than the target.
     */
    @Test
    void blocksAreSealedAtAboutTheTargetSize() throws IOException {
        try (SsTableWriter writer = SsTableWriter.create(table())) {
            for (int i = 0; i < 2_000; i++) {
                writer.add(b(String.format("key%05d", i)), b("v".repeat(50)));
            }
            writer.finish();
        }

        Parser parser = new Parser(Files.readAllBytes(table()));
        for (Block block : parser.blocks()) {
            int entryBytes = block.length - SsTableFormat.CHECKSUM_BYTES;
            assertThat(block.entries.size() == 1 || entryBytes <= SsTableFormat.TARGET_BLOCK_BYTES)
                    .describedAs("block of %d entries, %d bytes", block.entries.size(), entryBytes)
                    .isTrue();
        }
    }

    /**
     * Catches: the empty-block guard going missing, which would spin forever, and
     * an oversized entry being split or refused.
     */
    @Test
    void anEntryLargerThanTheTargetGetsABlockOfItsOwn() throws IOException {
        byte[] huge = new byte[50_000];
        Arrays.fill(huge, (byte) 'x');

        try (SsTableWriter writer = SsTableWriter.create(table())) {
            writer.add(b("a-before"), b("small"));
            writer.add(b("b-huge"), huge);
            writer.add(b("c-after"), b("small"));
            writer.finish();
        }

        Parser parser = new Parser(Files.readAllBytes(table()));
        Block hugeBlock = parser.blocks().stream()
                .filter(block -> block.entries.stream().anyMatch(e -> s(e.key).equals("b-huge")))
                .findFirst()
                .orElseThrow();

        assertThat(hugeBlock.entries).hasSize(1);
        assertThat(hugeBlock.length).isGreaterThan(SsTableFormat.TARGET_BLOCK_BYTES);
        assertThat(parser.allEntries().stream().map(e -> s(e.key)).toList())
                .containsExactly("a-before", "b-huge", "c-after");
    }

    /** Catches: a tombstone written with a value section, or as an ordinary entry. */
    @Test
    void aTombstoneHasNoValueSection() throws IOException {
        try (SsTableWriter writer = SsTableWriter.create(table())) {
            writer.add(b("alive"), b("yes"));
            writer.addTombstone(b("dead"));
            writer.finish();
        }

        List<Entry> entries = new Parser(Files.readAllBytes(table())).allEntries();

        assertThat(entries.get(1).kind).isEqualTo(SsTableFormat.KIND_TOMBSTONE);
        assertThat(entries.get(1).value).isNull();
        assertThat(s(entries.get(1).key)).isEqualTo("dead");
    }

    /**
     * Catches: an index that does not describe the file. Offsets must be
     * contiguous and first keys must match the blocks they point at, or a lookup
     * reads the wrong bytes.
     */
    @Test
    void theIndexNamesEveryBlockInOrderWithContiguousOffsets() throws IOException {
        try (SsTableWriter writer = SsTableWriter.create(table())) {
            for (int i = 0; i < 1_000; i++) {
                writer.add(b(String.format("key%05d", i)), b("value-" + i));
            }
            writer.finish();
        }

        Parser parser = new Parser(Files.readAllBytes(table()));
        List<IndexRow> index = parser.index();
        List<Block> blocks = parser.blocks();

        assertThat(index).hasSameSizeAs(blocks);

        long expectedOffset = 0;
        for (int i = 0; i < index.size(); i++) {
            assertThat(index.get(i).offset).isEqualTo(expectedOffset);
            assertThat(index.get(i).length).isEqualTo(blocks.get(i).length);
            assertThat(s(index.get(i).firstKey)).isEqualTo(s(blocks.get(i).entries.get(0).key));
            expectedOffset += index.get(i).length;

            if (i > 0) {
                assertThat(Arrays.compareUnsigned(index.get(i - 1).firstKey, index.get(i).firstKey))
                        .isNegative();
            }
        }
    }

    /** Catches: out-of-order keys, which would make the index point at nonsense. */
    @Test
    void keysMustArriveInAscendingOrder() throws IOException {
        try (SsTableWriter writer = SsTableWriter.create(table())) {
            writer.add(b("b"), b("1"));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> writer.add(b("a"), b("2")))
                    .withMessageContaining("ascending");
        }
    }

    /** Catches: the same key twice in one file, which has no defined winner. */
    @Test
    void theSameKeyCannotBeAddedTwice() throws IOException {
        try (SsTableWriter writer = SsTableWriter.create(table())) {
            writer.add(b("k"), b("1"));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> writer.add(b("k"), b("2")));
        }
    }

    /** Catches: an empty flush producing an unreadable file rather than an empty one. */
    @Test
    void anEmptyTableIsStillAValidFile() throws IOException {
        try (SsTableWriter writer = SsTableWriter.create(table())) {
            SsTableInfo info = writer.finish();

            assertThat(info.isEmpty()).isTrue();
            assertThat(info.blockCount()).isZero();
        }

        Parser parser = new Parser(Files.readAllBytes(table()));

        assertThat(parser.footer().magic).isEqualTo("IRONCLAD");
        assertThat(parser.footer().entryCount).isZero();
        assertThat(parser.index()).isEmpty();
        assertThat(parser.allEntries()).isEmpty();
    }

    /** Catches: the flush path losing or reordering what the memtable held. */
    @Test
    void flushingAMemtableWritesEveryPairInOrder() throws IOException {
        SkipList memtable = new SkipList();
        for (String key : List.of("pear", "apple", "fig", "cherry", "date")) {
            memtable.put(b(key), b(key.toUpperCase()));
        }

        SsTableInfo info = SsTableWriter.flush(memtable, table());

        assertThat(info.entryCount()).isEqualTo(5);
        assertThat(s(info.firstKey())).isEqualTo("apple");
        assertThat(s(info.lastKey())).isEqualTo("pear");

        List<Entry> entries = new Parser(Files.readAllBytes(table())).allEntries();
        assertThat(entries.stream().map(e -> s(e.key)).toList())
                .containsExactly("apple", "cherry", "date", "fig", "pear");
        assertThat(s(entries.get(0).value)).isEqualTo("APPLE");
    }

    /** Catches: a block checksum that is absent or does not cover the entries. */
    @Test
    void aFlippedByteInsideABlockBreaksThatBlocksChecksum() throws IOException {
        try (SsTableWriter writer = SsTableWriter.create(table())) {
            writer.add(b("a"), b("one"));
            writer.add(b("b"), b("two"));
            writer.finish();
        }

        byte[] bytes = Files.readAllBytes(table());
        bytes[3] ^= 0x01;                       // inside the first block's entries

        assertThatIllegalStateException()
                .isThrownBy(() -> new Parser(bytes).allEntries())
                .withMessageContaining("checksum");
    }

    // ------------------------------------------------- the independent parser

    private record Entry(byte kind, byte[] key, byte[] value) {}

    private record IndexRow(byte[] firstKey, long offset, int length) {}

    private record Block(long offset, int length, List<Entry> entries) {}

    private record Footer(long indexOffset, int indexLength, long entryCount, int version, String magic) {

        static Footer of(byte[] file) {
            ByteBuffer buffer = ByteBuffer.wrap(file, file.length - SsTableFormat.FOOTER_BYTES,
                    SsTableFormat.FOOTER_BYTES);
            long indexOffset = buffer.getLong();
            int indexLength = buffer.getInt();
            long entryCount = buffer.getLong();
            int version = buffer.getInt();
            byte[] magic = new byte[8];
            buffer.get(magic);
            return new Footer(indexOffset, indexLength, entryCount, version,
                    new String(magic, StandardCharsets.US_ASCII));
        }
    }

    /**
     * Reads a file using only the written format description: seek to the footer,
     * find the index, walk the blocks. Its varint decoding is written differently
     * from the production one on purpose.
     */
    private static final class Parser {

        private final byte[] file;
        private final Footer footer;

        Parser(byte[] file) {
            this.file = file;
            this.footer = Footer.of(file);
        }

        Footer footer() {
            return footer;
        }

        List<IndexRow> index() {
            ByteBuffer buffer = ByteBuffer.wrap(file, (int) footer.indexOffset, footer.indexLength);
            verifyChecksum(buffer, (int) footer.indexOffset, footer.indexLength, "index");

            List<IndexRow> rows = new ArrayList<>();
            int end = (int) footer.indexOffset + footer.indexLength - SsTableFormat.CHECKSUM_BYTES;
            while (buffer.position() < end) {
                byte[] key = new byte[varint(buffer)];
                buffer.get(key);
                rows.add(new IndexRow(key, buffer.getLong(), buffer.getInt()));
            }
            return rows;
        }

        List<Block> blocks() {
            List<Block> blocks = new ArrayList<>();
            for (IndexRow row : index()) {
                ByteBuffer buffer = ByteBuffer.wrap(file, (int) row.offset, row.length);
                verifyChecksum(buffer, (int) row.offset, row.length, "block");

                List<Entry> entries = new ArrayList<>();
                int end = (int) row.offset + row.length - SsTableFormat.CHECKSUM_BYTES;
                while (buffer.position() < end) {
                    byte kind = buffer.get();
                    byte[] key = new byte[varint(buffer)];
                    buffer.get(key);
                    byte[] value = null;
                    if (kind == SsTableFormat.KIND_VALUE) {
                        value = new byte[varint(buffer)];
                        buffer.get(value);
                    }
                    entries.add(new Entry(kind, key, value));
                }
                blocks.add(new Block(row.offset, row.length, entries));
            }
            return blocks;
        }

        List<Entry> allEntries() {
            List<Entry> all = new ArrayList<>();
            for (Block block : blocks()) {
                all.addAll(block.entries);
            }
            return all;
        }

        private void verifyChecksum(ByteBuffer buffer, int offset, int length, String what) {
            int bodyLength = length - SsTableFormat.CHECKSUM_BYTES;
            CRC32C crc = new CRC32C();
            crc.update(file, offset, bodyLength);
            int stored = ByteBuffer.wrap(file, offset + bodyLength, 4).getInt();
            if ((int) crc.getValue() != stored) {
                throw new IllegalStateException(what + " checksum mismatch at offset " + offset);
            }
        }

        /** Deliberately a different implementation from Varints.get. */
        private static int varint(ByteBuffer buffer) {
            int result = 0;
            while (true) {
                int next = buffer.get() & 0xFF;
                result = (result << 7) | (next & 0x7F);
                if (next < 0x80) {
                    return result;
                }
            }
        }
    }
}
