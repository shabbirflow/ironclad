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
 *
 * The shape of the work: entries pile up in one buffer until it is about 4 KB,
 * that buffer becomes a block on disk and a row in the index, and at the end the
 * index and a fixed-size footer are written behind the data.
 */
public final class SsTableWriter implements AutoCloseable {

    /**
     * One row of the index. firstKey answers "could the key I want be in this
     * block?", offset says where to seek, length says how much to read.
     */
    private record IndexEntry(byte[] firstKey, long offset, int length) {}

    /** The open file. Written forward only; we never seek backwards. */
    private final FileChannel channel;

    /**
     * The block being filled, in memory. A ByteBuffer's capacity is fixed when it
     * is allocated, so "growing" below means allocating a bigger one and copying.
     * Starts a little over the target so an ordinary entry never triggers that.
     */
    private ByteBuffer block;

    /** How many entries are in the block so far. Zero means the block is empty. */
    private int entriesInBlock;

    /** The first key of the block being filled; becomes this block's index row. */
    private byte[] firstKeyInBlock;

    /** One row per block written so far, in file order, which is also key order. */
    private final List<IndexEntry> index = new ArrayList<>();

    /** Bytes written to the file so far, so also where the next block will start. */
    private long fileOffset;

    /** Total key/value pairs, recorded in the footer for later sizing decisions. */
    private long entryCount;

    /** The key range this file covers; */
    private byte[] firstKey;
    private byte[] lastKey;

    /** Set by finish(), after which nothing more may be added. */
    private boolean finished;

    /** Creates or replaces the file. TRUNCATE_EXISTING so a retry starts clean. */
    public static SsTableWriter create(Path path) throws IOException {
        return new SsTableWriter(FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE));
    }

    /** Package-private so a test can hand in a channel it controls. */
    SsTableWriter(FileChannel channel) {
        this.channel = channel;
        // Target plus slack: an entry of a few hundred bytes can always be added
        // to a nearly full block without reallocating first.
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

    /** A normal key and value. */
    public void add(byte[] key, byte[] value) throws IOException {
        append(SsTableFormat.KIND_VALUE, key, value);
    }

    /**
     * A delete: the key is present with no value section, meaning "this is gone".
     * Needed because an older file on disk may still hold a value for this key and
     * cannot be edited, so the delete has to be written somewhere newer.
     */
    public void addTombstone(byte[] key) throws IOException {
        append(SsTableFormat.KIND_TOMBSTONE, key, null);
    }

    private void append(byte kind, byte[] key, byte[] value) throws IOException {
        if (finished) {
            throw new IllegalStateException("this table is already finished");
        }

        // Keys must arrive sorted and unique, because the index and every later
        // binary search assume it. Checking costs one comparison; a file written
        // out of order would simply fail to find keys that are in it, which is a
        // miserable bug to meet months later.
        if (lastKey != null && Arrays.compareUnsigned(lastKey, key) >= 0) {
            throw new IllegalArgumentException("keys must be added in ascending order, and only once");
        }

        // Work out the entry's size before writing any of it: kind byte, the key's
        // length as a varint and the key itself, then the same for the value.
        // A tombstone stops after the key, so it contributes nothing more.
        int entryBytes = 1 + Varints.length(key.length) + key.length
                + (value == null ? 0 : Varints.length(value.length) + value.length);

        // Seal the current block if this entry would push it past the target, but
        // only if there is something in it. An entry larger than the target would
        // otherwise find every fresh block too small as well.
        if (entriesInBlock > 0 && block.position() + entryBytes > SsTableFormat.TARGET_BLOCK_BYTES) {
            finishBlock();
        }

        // Make sure the buffer can hold this entry plus the checksum that will be
        // appended when the block is sealed. Usually a no-op.
        ensureRoom(entryBytes + SsTableFormat.CHECKSUM_BYTES);

        block.put(kind);                      // 1 = value, 2 = tombstone
        Varints.put(block, key.length);       // how many key bytes follow
        block.put(key);
        if (value != null) {
            Varints.put(block, value.length); // how many value bytes follow
            block.put(value);
        }

        // The first key to land in an empty block becomes that block's index key.
        if (entriesInBlock == 0) {
            firstKeyInBlock = key;
        }
        entriesInBlock++;
        entryCount++;

        if (firstKey == null) {
            firstKey = key;                   // the smallest key in the whole file
        }
        lastKey = key;                        // so far; the last one sticks
    }

    /**
     * Appends the checksum, writes the block to disk, and records where it went.
     *
     * Returns immediately on an empty block, so sealing twice in a row, or sealing
     * before the first entry, writes nothing.
     */
    private void finishBlock() throws IOException {
        if (entriesInBlock == 0) {
            return;
        }

        // Checksum covers exactly the entries written so far, which is everything
        // from index 0 up to the cursor.
        CRC32C crc = new CRC32C();
        crc.update(block.array(), 0, block.position());
        block.putInt((int) crc.getValue());   // the checksum is the block's last 4 bytes

        int length = block.position();        // entries plus that checksum
        writeFully(block.flip());             // flip: stop filling, start reading

        // One index row per block: this block's first key, where it starts in the
        // file, and how long it is. A reader needs all three.
        index.add(new IndexEntry(firstKeyInBlock, fileOffset, length));

        fileOffset += length;                 // the next block starts here
        block.clear();                        // forget the bytes, reuse the buffer
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

        finishBlock();                        // whatever was still being filled

        long indexOffset = fileOffset;        // the index starts right after the data
        int indexLength = writeIndex();
        writeFooter(indexOffset, indexLength);

        // One force for the whole file: nothing in it is usable until the footer
        // lands, so there is nothing worth protecting before that point.
        channel.force(true);
        finished = true;

        return new SsTableInfo(entryCount, index.size(), fileOffset, firstKey, lastKey);
    }

    /**
     * Writes every index row, then a checksum over all of them, and returns how
     * many bytes that took so the footer can record it.
     *
     * Two passes over the rows: the first adds up the exact size, the second fills
     * a buffer of precisely that size. Measuring first means no growing, no
     * copying, and no guessing.
     */
    private int writeIndex() throws IOException {
        int bytes = SsTableFormat.CHECKSUM_BYTES;
        for (IndexEntry entry : index) {
            // varint key length + the key + an 8-byte offset + a 4-byte length
            bytes += Varints.length(entry.firstKey().length) + entry.firstKey().length + 8 + 4;
        }

        ByteBuffer buffer = ByteBuffer.allocate(bytes);
        for (IndexEntry entry : index) {
            Varints.put(buffer, entry.firstKey().length);
            buffer.put(entry.firstKey());
            buffer.putLong(entry.offset());   // long: a file can be larger than 2 GB
            buffer.putInt(entry.length());    // int: one block is never that big
        }

        // Same deal as a data block: the index is checksummed because it is read
        // as a unit, and a corrupt index sends every lookup to the wrong bytes.
        CRC32C crc = new CRC32C();
        crc.update(buffer.array(), 0, buffer.position());
        buffer.putInt((int) crc.getValue());

        writeFully(buffer.flip());
        fileOffset += bytes;
        return bytes;
    }

    /**
     * The last 32 bytes: where the index is, how big it is, how many entries the
     * file holds, the format version, and the magic.
     *
     * Fixed size is the point. A reader knows nothing about this file, so it seeks
     * to fileSize - 32 and starts there.
     */
    private void writeFooter(long indexOffset, int indexLength) throws IOException {
        ByteBuffer footer = ByteBuffer.allocate(SsTableFormat.FOOTER_BYTES);
        footer.putLong(indexOffset);          // 8
        footer.putInt(indexLength);           // 4
        footer.putLong(entryCount);           // 8
        footer.putInt(SsTableFormat.VERSION); // 4
        footer.put(SsTableFormat.MAGIC);      // 8, and last: its presence proves
                                              // the writer reached the end
        writeFully(footer.flip());
        fileOffset += SsTableFormat.FOOTER_BYTES;
    }

    /**
     * write() may take fewer bytes than offered; the API promises nothing more, so
     * keep going until the buffer is empty. A short write left here would be a
     * half-written block that we created ourselves.
     */
    private void writeFully(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /**
     * A ByteBuffer cannot be resized, so this allocates a larger one and copies
     * what is already there. Only happens when a single entry is bigger than the
     * usual block, which is legal and must not be refused.
     */
    private void ensureRoom(int needed) {
        if (block.remaining() >= needed) {
            return;                           // the common case: nothing to do
        }
        int size = Math.max(block.capacity() * 2, block.position() + needed);
        ByteBuffer bigger = ByteBuffer.allocate(size);
        bigger.put(block.flip());             // flip: read what is there, copy it over
        block = bigger;                       // and from now on use the bigger one
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
