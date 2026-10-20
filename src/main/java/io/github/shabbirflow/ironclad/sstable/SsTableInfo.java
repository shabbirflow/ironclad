package io.github.shabbirflow.ironclad.sstable;

/**
 * IN ONE LINE: what the writer produced, so the caller does not have to reopen
 * the file to find out.
 *
 * firstKey and lastKey are the key range the file covers. Milestone 1.5 uses them
 * to skip a file entirely when the key being looked up falls outside it, and 1.7
 * uses them to decide which files overlap and therefore have to be merged
 * together.
 */
public record SsTableInfo(
        long entryCount,
        int blockCount,
        long fileBytes,
        byte[] firstKey,
        byte[] lastKey) {

    public boolean isEmpty() {
        return entryCount == 0;
    }
}
