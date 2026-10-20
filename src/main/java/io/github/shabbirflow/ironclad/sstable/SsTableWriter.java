package io.github.shabbirflow.ironclad.sstable;

import io.github.shabbirflow.ironclad.bytes.Varints;
import io.github.shabbirflow.ironclad.storage.SkipList;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * IN ONE LINE: writes a sorted run of entries out as one immutable file, in a
 * single forward pass.
 *
 * Invariant: when finish() returns, the file is a complete SSTable ending in the
 * magic bytes, every block's checksum covers its own entries, and the index names
 * exactly one block per block written, in key order.
 *
 * The magic lands last on purpose. Until it does, the file is not a valid SSTable
 * and the write-ahead log still holds every record, so an interrupted flush costs
 * nothing but a deleted file and a replay.
 */
public final class SsTableWriter implements AutoCloseable {

    /** One row of the index: where a block is, and the first key inside it. */
    private record IndexEntry(byte[] firstKey, long offset, int length) {}

    private final FileChannel channel;

    /** The block being filled. Grows only if one entry is larger than the target. */
    private ByteBuffer block;
    private int entriesInBlock;
    private byte[] firstKeyInBlock;

    private final List<IndexEntry> index = new ArrayList<>();
    private long fileOffset;
    private long entryCount;
    private byte[] firstKey;
    private byte[] lastKey;
    private boolean finished;

    public static SsTableWriter create(Path path) throws IOException {
        return new SsTableWriter(FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE));
    }

    SsTableWriter(FileChannel channel) {
        this.channel = channel;
        this.block = ByteBuffer.allocate(SsTableFormat.TARGET_BLOCK_BYTES + 1024);
    }

    /**
     * Writes every entry of a memtable to a new file, in key order, which is what
     * lane 0 of the skip list already is: a flush does no sorting at all.
     */
    public static SsTableInfo flush(SkipList memtable, Path path) throws IOException {
        try (SsTableWriter writer = create(path)) {
            for (SkipList.Entry entry : memtable) {
                writer.add(entry.key(), entry.value());
            }
            return writer.finish();
        }
    }

    public void add(byte[] key, byte[] value) throws IOException {
        append(SsTableFormat.KIND_VALUE, key, value);
    }

    /** A delete: the key is present, with no value section, meaning "this is gone". */
    public void addTombstone(byte[] key) throws IOException {
        append(SsTableFormat.KIND_TOMBSTONE, key, null);
    }

    private void append(byte kind, byte[] key, byte[] value) throws IOException {
        if (finished) {
            throw new IllegalStateException("this table is already finished");
        }
        // Keys must arrive sorted, because the index and every later binary search
        // assume it. Checking is nearly free here, and a file written out of order
        // would simply fail to find keys that are in it, which is a miserable bug
        // to meet months later.
        if (lastKey != null && Arrays.compareUnsigned(lastKey, key) >= 0) {
            throw new IllegalArgumentException("keys must be added in ascending order, and only once");
        }

        int entryBytes = 1 + Varints.length(key.length) + key.length
                + (value == null ? 0 : Varints.length(value.length) + value.length);

        // Seal the current block only if there is something in it. An entry larger
        // than the target would otherwise seal an empty block, find the new block
        // still too small, and seal again, forever.
        if (entriesInBlock > 0 && block.position() + entryBytes > SsTableFormat.TARGET_BLOCK_BYTES) {
            finishBlock();
        }
        ensureRoom(entryBytes + SsTableFormat.CHECKSUM_BYTES);

        block.put(kind);
        Varints.put(block, key.length);
        block.put(key);
        if (value != null) {
            Varints.put(block, value.length);
            block.put(value);
        }

        if (entriesInBlock == 0) {
            firstKeyInBlock = key;
        }
        entriesInBlock++;
        entryCount++;
        if (firstKey == null) {
            firstKey = key;
        }
        lastKey = key;
    }

    /** Appends the checksum, writes the block out, and records it in the index. */
    private void finishBlock() throws IOException {
        if (entriesInBlock == 0) {
            return;
        }
        CRC32C crc = new CRC32C();
        crc.update(block.array(), 0, block.position());
        block.putInt((int) crc.getValue());

        int length = block.position();
        writeFully(block.flip());
        index.add(new IndexEntry(firstKeyInBlock, fileOffset, length));

        fileOffset += length;
        block.clear();
        entriesInBlock = 0;
        firstKeyInBlock = null;
    }

    /**
     * Seals the last block, writes the index and the footer, and makes the whole
     * file durable. Returning means the file is complete and readable.
     */
    public SsTableInfo finish() throws IOException {
        if (finished) {
            throw new IllegalStateException("this table is already finished");
        }
        finishBlock();

        long indexOffset = fileOffset;
        int indexLength = writeIndex();
        writeFooter(indexOffset, indexLength);

        // One force for the whole file: nothing in it is usable until the footer
        // lands, so there is nothing to protect before that.
        channel.force(true);
        finished = true;

        return new SsTableInfo(entryCount, index.size(), fileOffset, firstKey, lastKey);
    }

    private int writeIndex() throws IOException {
        int bytes = SsTableFormat.CHECKSUM_BYTES;
        for (IndexEntry entry : index) {
            bytes += Varints.length(entry.firstKey().length) + entry.firstKey().length + 8 + 4;
        }

        ByteBuffer buffer = ByteBuffer.allocate(bytes);
        for (IndexEntry entry : index) {
            Varints.put(buffer, entry.firstKey().length);
            buffer.put(entry.firstKey());
            buffer.putLong(entry.offset());
            buffer.putInt(entry.length());
        }
        CRC32C crc = new CRC32C();
        crc.update(buffer.array(), 0, buffer.position());
        buffer.putInt((int) crc.getValue());

        writeFully(buffer.flip());
        fileOffset += bytes;
        return bytes;
    }

    private void writeFooter(long indexOffset, int indexLength) throws IOException {
        ByteBuffer footer = ByteBuffer.allocate(SsTableFormat.FOOTER_BYTES);
        footer.putLong(indexOffset);
        footer.putInt(indexLength);
        footer.putLong(entryCount);
        footer.putInt(SsTableFormat.VERSION);
        footer.put(SsTableFormat.MAGIC);        // last, so its presence proves completeness

        writeFully(footer.flip());
        fileOffset += SsTableFormat.FOOTER_BYTES;
    }

    /** write() may take fewer bytes than offered; the API promises nothing more. */
    private void writeFully(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /** Grows the block buffer when a single entry does not fit the usual size. */
    private void ensureRoom(int needed) {
        if (block.remaining() >= needed) {
            return;
        }
        int size = Math.max(block.capacity() * 2, block.position() + needed);
        ByteBuffer bigger = ByteBuffer.allocate(size);
        bigger.put(block.flip());
        block = bigger;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
