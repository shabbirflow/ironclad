# Ironclad

A distributed key-value database in Java, built from the storage engine up, with
no database libraries. The goal is a store that keeps its promises while you
attack it: kill the leader, sever the network, restart nodes at random, and it
should lose nothing it ever acknowledged and never show a client an impossible
result.

**Status: in progress.** The storage engine is being built first. Nothing here is
production software; it is a learning project with production discipline.

```
   Layer 4   Sharding      many Raft groups                      not started
   Layer 3   Raft          five machines agree on one truth      not started
   Layer 2   MVCC          readers and writers stop blocking     not started
   Layer 1   Storage       survives kill -9                      in progress
```

## What works today

- **Skip-list memtable** — written by hand rather than using
  `ConcurrentSkipListMap`, then benchmarked against it with JMH. It loses, and
  [DECISIONS.md](DECISIONS.md) explains why: our node holds its tower in a
  separate array, so every hop costs two dereferences instead of one.
- **Write-ahead log** — records framed with a length and a CRC32C, `fsync` before
  any write is acknowledged, and recovery that replays the longest intact prefix
  and truncates whatever a crash left behind. A torn tail is told apart from
  corruption, because they mean different things.
- **SSTable writer** — a hand-designed immutable file format: ~4 KB data blocks,
  a checksum per block, a sparse index of one row per block, and a 32-byte footer
  whose magic bytes are written last, so their presence proves the file is
  complete.
- **Maelstrom client** — the node speaks Jepsen's
  [Maelstrom](https://github.com/jepsen-io/maelstrom) protocol over stdin and
  stdout. The `echo` workload passes, which is what proves Java, Gradle, WSL and
  Maelstrom work together before Raft depends on it.

59 tests, run on every push. Several of them have been
[mutation-checked](DECISIONS.md) by deliberately breaking the code to confirm the
tests notice.

## The file formats

The same pair, `user:42` → `shabbir`, 14 bytes of information:

| | memtable | write-ahead log | SSTable |
|---|---|---|---|
| costs | ~96 B | 25 B | ~17.3 B |
| framing unit | a Java object | one record | one ~4 KB block |
| checksum unit | none | one record | one block |
| must survive | nothing | a power cut mid-write | being read for years |

Framing is sized to the failure it defends against. The log can be torn
mid-record, so it pays per record. An SSTable is never modified once written, so
one checksum covers roughly 240 entries. The memtable can be lost entirely
without consequence, so it pays nothing for durability and spends it all on
pointers.

```
SSTable:  [ data block ][ data block ] … [ index block ][ footer : 32 B ]
block:    [ entry ][ entry ] … [ crc32c : 4 ]
entry:    [ kind : 1 ][ keyLen : varint ][ key ][ valueLen : varint ][ value ]
index:    one row per block — [ keyLen : varint ][ first key ][ offset : 8 ][ length : 4 ]
footer:   [ indexOffset : 8 ][ indexLength : 4 ][ entryCount : 8 ][ version : 4 ][ magic : 8 ]
```

A lookup reads the last 32 bytes, then the index, then **one block**. Two small
reads to open a file of any size, and one read per key after that.

## Running it

```bash
./gradlew test          # 59 tests
./gradlew jmh           # benchmarks: -Pbench=<regex> to narrow the run
./gradlew installDist   # builds build/install/ironclad/bin/ironclad
```

The Maelstrom harness is a Unix program, so on Windows it runs under WSL2 while
the code is built natively:

```bash
~/maelstrom/maelstrom test -w echo \
  --bin /mnt/c/Users/shabb/dev/ironclad/build/install/ironclad/bin/ironclad \
  --node-count 1 --time-limit 5
```

**Requirements:** JDK 21+. For Maelstrom: WSL2 with a JDK, graphviz and gnuplot.

## The documents are the point

- **[DECISIONS.md](DECISIONS.md)** — every design decision, what was rejected, and
  what was measured. It includes the things that turned out to be wrong: an
  allocation theory the GC profiler killed, a checksum argument that no test could
  actually support, and a benchmark run where the machine swung 7× between
  measurements.
- **[docs/questions.md](docs/questions.md)** — the questions asked while building
  it, answered.
- **[docs/deferred.md](docs/deferred.md)** — simpler choices made on purpose, each
  with the trigger that should bring it back.

## Built on

Java 21, Gradle, JUnit 5, AssertJ, JMH, Jackson for the Maelstrom protocol, and
nothing else. No Guava, no Netty, no embedded database. The parts you would
normally reach for a library to get are the parts worth building.
