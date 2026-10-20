package io.github.shabbirflow.ironclad.sstable;

import java.nio.charset.StandardCharsets;

/**
 * IN ONE LINE: the numbers and byte patterns that define what an SSTable file
 * looks like, in one place so the writer and the reader cannot disagree.
 *
 * The file:
 *
 *   [ data block 0 ][ data block 1 ] ... [ index block ][ footer : 32 bytes ]
 *
 * A block:
 *
 *   [ entry ][ entry ] ... [ crc32c : 4 ]        checksum covers the entries
 *
 * An entry:
 *
 *   [ kind : 1 ][ keyLen : varint ][ key ][ valueLen : varint ][ value ]
 *   kind 1 = value, kind 2 = tombstone, which stops after the key
 *
 * An index entry, one per data block:
 *
 *   [ keyLen : varint ][ first key of the block ][ offset : 8 ][ length : 4 ]
 *
 * The footer, always the last 32 bytes:
 *
 *   [ indexOffset : 8 ][ indexLength : 4 ][ entryCount : 8 ][ version : 4 ][ magic : 8 ]
 *
 * Why the index is at the end: its contents are unknown until every block has
 * been written, and we write forward without seeking back. Why the footer is a
 * fixed size: a reader can always seek to fileSize - 32 and find it, knowing
 * nothing else about the file.
 */
public final class SsTableFormat {

    /**
     * The last eight bytes of every complete file. Two jobs: say "this is an
     * Ironclad SSTable" before anything else is believed, and prove the writer
     * reached the end, because the magic is the very last thing written. A flush
     * interrupted by a crash leaves a file without it, which is deleted and redone
     * from the write-ahead log.
     */
    public static final byte[] MAGIC = "IRONCLAD".getBytes(StandardCharsets.US_ASCII);

    /**
     * Bumped whenever the layout changes. A reader that meets a version it does
     * not know refuses loudly, instead of misparsing one field and returning a key
     * that is half of one key and half of another.
     */
    public static final int VERSION = 1;

    /** indexOffset(8) + indexLength(4) + entryCount(8) + version(4) + magic(8). */
    public static final int FOOTER_BYTES = 32;

    /** Checksum per block, not per file: you verify what you actually read. */
    public static final int CHECKSUM_BYTES = 4;

    /**
     * Blocks are sealed at roughly this size because the operating system reads
     * in pages of about this size anyway. One lookup then costs one page read.
     * "Roughly", because an entry is never split, and a single entry larger than
     * this gets a block to itself.
     */
    public static final int TARGET_BLOCK_BYTES = 4096;

    public static final byte KIND_VALUE = 1;
    public static final byte KIND_TOMBSTONE = 2;

    private SsTableFormat() {
    }
}
