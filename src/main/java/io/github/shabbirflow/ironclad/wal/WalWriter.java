package io.github.shabbirflow.ironclad.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * IN ONE LINE: appends records to the log and does not return until they are on
 * the disk.
 *
 * Invariant: append returns only once that record's bytes are durable, and the
 * file is always a sequence of whole records followed by at most one partial one.
 *
 * The second half is what makes recovery possible: whatever a crash leaves
 * behind, the readable prefix is a real sequence of operations.
 */
public final class WalWriter implements AutoCloseable {

    private final FileChannel channel;

    /**
     * Set the moment a write or force fails, and never cleared. Bugs live here:
     * when force() throws we do not know whether the bytes reached the disk, so
     * the only honest move is to stop. Appending record 58 while 57's durability
     * is unknown makes the whole prefix unreasonable about.
     *
     * Postgres learned this the hard way in 2018: on Linux a failed fsync can
     * drop the dirty pages and report the error only once, so a later fsync
     * returns success for data that is gone. Their fix was to panic the server.
     */
    private IOException durabilityFailure;

    private long bytesAppended;

    /** Opens, creating the file if it is not there, with every write going to the end. */
    public static WalWriter open(Path path) throws IOException {
        return new WalWriter(FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND));
    }

    /** Package-private so tests can hand in a channel that is about to fail. */
    WalWriter(FileChannel channel) {
        this.channel = channel;
    }

    /**
     * Writes the record and makes it durable. Returning means the caller may
     * acknowledge it; throwing means they must not.
     */
    public void append(WalRecord record) throws IOException {
        if (durabilityFailure != null) {
            throw new IllegalStateException(
                    "the log stopped accepting writes after a durability failure", durabilityFailure);
        }

        ByteBuffer bytes = WalCodec.encode(record);
        int recordBytes = bytes.remaining();
        try {
            // write() may accept fewer bytes than we gave it. The API promises
            // nothing better, so keep going until the buffer is empty. A partial
            // write left here would be a torn record we created ourselves.
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }

            // Now the bytes are in the operating system's page cache, which a
            // kill -9 survives and a power cut does not. force(false) pushes them
            // to the device: data, plus the metadata needed to find it, which
            // includes the file's new length. force(true) would also flush
            // timestamps we never read.
            channel.force(false);
        } catch (IOException e) {
            durabilityFailure = e;
            throw e;
        }

        bytesAppended += recordBytes;
    }

    /** Total bytes handed to the log by this writer, header included. */
    public long bytesAppended() {
        return bytesAppended;
    }

    /** True once a write or force has failed, after which every append throws. */
    public boolean isStopped() {
        return durabilityFailure != null;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
