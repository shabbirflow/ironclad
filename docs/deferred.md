# Deferred on purpose

Decisions taken knowing they are not the final answer, each with the trigger that
should bring it back. Not a wish list: every item here is a thing we chose to do
later for a stated reason.

## WAL durability: upgrade from fsync-per-write

**Now:** `force()` on every record, acknowledge after it returns. Nothing
acknowledged is ever lost, at roughly 100 to 1000 writes a second.

**Later:** group commit (option B1). Collect the writes that are in flight at the
same moment, `force()` once for all of them, then acknowledge them all. Still
loses nothing, and throughput multiplies because many writes share one fsync.
Option B2, fsync on a timer with immediate acknowledgement, is faster still and
loses a bounded window of acknowledged writes; it is a knob to offer, not a
default.

**Trigger:** the first time several writes are genuinely in flight at once. Today
the node is single-threaded, so there is nothing to batch and B1 would be A with
extra machinery. Revisit when Phase 3 puts many clients on one leader, or at
milestone 1.9 when there are throughput numbers to beat.

**What to measure:** writes per second and p99 latency at 1, 8 and 64 concurrent
writers, for A, B1 and B2 side by side. Keep the fsync call in one place so
swapping is a small change.

## Emptying the log

**Now:** the log is never trimmed except for a torn tail. Good records stay
forever, so the file grows without bound and recovery gets slower on every
restart.

**Later:** rotate into segments (`wal-000001.log`, `wal-000002.log`, always
rotating **on a record boundary** so no record spans two files), and delete a
segment once a memtable flush has written its records to an SSTable in sorted
form. Deleting is only possible per file, because bytes cannot be removed from
the front of a file.

**Trigger:** milestone 1.3, the SSTable writer. Flushing is what first makes old
log records redundant, so there is nothing to delete before it exists.

**What to measure:** recovery time against log size, which milestone 1.9 asks for
anyway, and the pause a flush-plus-delete introduces.

## Skip list node layout

**Now:** each node holds its tower as a `Node[]`, so one hop is two
dereferences. We measured ourselves losing to `ConcurrentSkipListMap`, and a bare
linked list over the same keys lands near the JDK, which points at that
indirection.

**Later:** fixed-size node arrays, or an arena of primitives, so a hop is one
dereference.

**Trigger:** milestone 1.9, when there is an fsync in the same write path to
compare against. Right now our entire disadvantage is a few percent of a single
fsync, so the work cannot show up.

## A Memtable interface

**Now:** `SkipList` is used directly.

**Later:** a small `Memtable` interface, so `ConcurrentSkipListMap` stays a
swappable baseline and the engine does not depend on our implementation.

**Trigger:** milestone 1.3, when the flush path needs something to talk to.

## Scratch array in findPredecessors

**Now:** `put` allocates a `Node[MAX_HEIGHT]` per call.

**Later:** reuse one array per writer thread.

**Trigger:** only if 1.9's allocation profile shows it. The gc profiler already
caught me being wrong about allocation once.

## Java 21 or 25

**Now:** Java 21 toolchain, matching the installed JDK.

**Later:** the Foreign Memory API (`MemorySegment`, `Arena`) is preview on 21 and
final on 22.

**Trigger:** before milestone 1.4, the SSTable reader, which is where off-heap
access starts to matter.

## A trustworthy benchmark environment

**Now:** numbers come from a laptop where the same scan measured 153 and 20
ops/us minutes apart. Only within-run comparisons are used.

**Later:** a quiet machine, pinned CPU frequency, more forks, and a methodology
note beside every published number.

**Trigger:** before any number leaves the repo, which the build plan wants in
Phase 5.
