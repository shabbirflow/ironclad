# Decisions

## 2026-09-28 — Run Maelstrom under WSL2, build on Windows

**Context.** Maelstrom is a Clojure program started by a bash script. It launches
each node as a Unix process, talks to it over stdin/stdout, and needs graphviz
and gnuplot for its reports. My machine is Windows 11.

**Chose.** Run Maelstrom inside WSL2 (Ubuntu, OpenJDK 21+, graphviz, gnuplot).
Code and builds stay on Windows (IntelliJ + gradlew). WSL reads the repo
directly at /mnt/c/Users/shabb/dev/ironclad, so nothing is copied.

**Rejected.**
- Git Bash on Windows. Maelstrom would still be a Windows Java process, so
  Windows would still be the thing launching my node, and Windows picks how to
  launch a file by its extension. The launcher script has none.
- Docker. An extra layer to debug, and on Windows it runs on WSL2 anyway.
- A Linux VM or dual boot. More machine than the problem needs.

**Known cost.** Reading files across /mnt/c is slower, so each node's JVM starts
more slowly. Irrelevant for echo. If it ever affects Raft timing tests, copy
build/install/ironclad into the WSL filesystem first (and chmod +x the script,
since Linux filesystems keep a real execute bit and /mnt/c fakes one).

## 2026-09-28 — Gradle application plugin, not a fat jar

**Problem.** Maelstrom's --bin takes the path of a file it can execute. Java
needs `java -classpath <every jar> <main class>`, and the dependency jars live
in Gradle's cache under hashed paths.

**Chose.** The `application` plugin. `./gradlew installDist` copies my jar and
every dependency jar into build/install/ironclad/lib, and generates
build/install/ironclad/bin/ironclad: a /bin/sh script that resolves its own
location, builds the classpath from its own lib/, and execs the JVM. Maelstrom
points at that script. The classpath is regenerated on every build, so adding a
dependency needs no manual work.

**Rejected.**
- Fat jar plus a hand-written wrapper. Needs an extra plugin or hand-rolled jar
  merging, jars can collide at runtime rather than build time, and a wrapper
  script would still have to be written and maintained.
- Pointing --bin at `./gradlew run`. Two fatal problems: Gradle writes progress
  output to stdout, which corrupts the message stream, and its `run` task feeds
  the program an empty stdin by default, so no message ever arrives.

**Also set.**
- Java 21 toolchain, matching the installed JDK. The JVM inside WSL must be 21
  or newer or it refuses the bytecode ("unsupported class file major version").
  Open question: the Foreign Memory API (MemorySegment, Arena) is preview on 21
  and final on 22. Decide whether to move to 25 before milestone 1.4.
- Jackson 2.19.2 as the only implementation dependency, for the Maelstrom
  protocol only. Test libraries get added when a milestone needs them.
- rootProject.name = "ironclad", so the launcher is bin/ironclad rather than
  bin/ironclad_db. Package renamed org.example -> io.github.shabbirflow.ironclad.

## 2026-09-30 — Milestone 0.3: the Maelstrom client

**Built.** `Message` (the src/dest/body envelope), `Body` (sealed interface, one
record per type), `Node` (the logic, currently echo), `MaelstromClient` (read a
line, parse, ask the Node, write one flushed line), `Main` (wires the real pipes).
Six JUnit tests, then the real gate: `maelstrom test -w echo` reports
`:valid? true`, 25 of 25 operations OK.

**Invariant.** Every request read from stdin produces exactly one complete,
flushed JSON line on stdout, addressed to its sender with `in_reply_to` set to
that request's `msg_id`, and nothing else ever reaches stdout.

**Chose.**
- Records + sealed interface + exhaustive switch (option A) over a generic
  `JsonNode` body. Costs Jackson annotations; buys a compile error instead of a
  silently unhandled message type. With 15 Raft message types that trade is
  obvious, and the build plan asks for this shape in 3.1 anyway.
- `Body.Unknown` as Jackson's `defaultImpl`, so an unmodelled type costs one
  error reply (code 10) instead of the run.
- `Long msgId`, not `long`: absent means null, where a primitive would silently
  become 0 and produce `in_reply_to: 0`, matching no request.
- The client takes a `Reader`/`Writer`, not `System.in`/`System.out`, so tests
  drive it with strings in milliseconds.
- Single-threaded loop. Raft will add timers, and at that point stdout needs one
  dedicated writer, because two threads writing half-lines splice them together.
- `out.write('\n')` rather than `newLine()`, which would emit `\r\n` on Windows
  and break framing.
- Explicit UTF-8 in `Main`, not the platform default.
- Unparseable input line: log to stderr and continue, never fatal.

**Rejected.** Replying to `*_ok` bodies. A reply to a reply ping-pongs forever
between two nodes; `handle` returns `Optional.empty()` for those, and a test
pins it.

**Not done, deliberately.** Gossip Glomers challenges 2 and 3. Unique-ids is
optional and broadcast teaches gossip, which Raft does not use. Echo passing is
what 0.3 existed to prove: Java, Gradle, WSL and Maelstrom work together.

## 2026-10-05 — Milestone 0.4: CI and the benchmark harness

**Built.** `.github/workflows/ci.yml` running `./gradlew test` plus
`jmhClasses` on every push and pull request, and one JMH benchmark
(`MessageParseBenchmark`) measuring the cost of parsing a Maelstrom message.
First number: 2.14 ops/us, around 2.1 million messages a second.

**Chose.**
- The `me.champeau.jmh` Gradle plugin (0.7.3) over wiring the annotation
  processor and source set by hand. One line of config instead of fifteen, and
  benchmark plumbing is not where the learning budget belongs.
- A benchmark that measures something real (message parsing) rather than a
  placeholder, so the number is worth looking at even though the point was to
  prove the harness runs.
- Small iteration counts (1 fork, 3 warmup, 3 measurement) so the smoke test
  takes seconds. Real runs at 1.1 will use proper counts.
- CI compiles benchmarks but does not run them: shared CI runners have noisy
  neighbours, so timings from them would be worthless.
- Unit tests only in CI. Maelstrom runs need Maelstrom installed and take
  minutes; they stay local and deliberate.

**Fixed.** git had `gradlew` as mode 100644, with no execute bit, because
Windows does not track one. `./gradlew` on the Linux runner would have failed
with "Permission denied" on the very first CI run. `git update-index --chmod=+x`
records it. Exactly the trap from lesson 2, met for real.

## 2026-10-05 — Milestone 1.1: the skip list, and losing to the JDK

**Built.** `SkipList`: byte[] keys ordered by `Arrays.compareUnsigned`, coin-flip
heights capped at 12 lanes, head sentinel, `put` / `get` / `remove` / `Iterable`,
running byte total for the future flush threshold. 16 tests with `TreeMap` as the
oracle, including 30,000 random put/get/remove operations under fixed seeds.

**Chose.**
- Single-threaded (option 1A). A wrong answer should have exactly one possible
  cause. Concurrency gets its own milestone, with these tests and this benchmark
  already in place to price it.
- `byte[]` keys (option 2A), because that is what the SSTables will hold, and
  `String` ordering is UTF-16 code-unit order, not unsigned byte order. Changing
  key type later would silently change sort order, which the whole engine rests on.
- `remove` physically unlinks the node rather than storing a tombstone. This
  class is a sorted map; shadowing values that already reached disk is the
  engine's rule, and belongs in milestone 1.5. It also keeps the comparison with
  ConcurrentSkipListMap honest, since that is what its `remove` does.
- `Iterable<Entry>` rather than a `Consumer`, so flushing and milestone 1.8's
  merge iterator both read as ordinary loops.
- An injectable `Random`, so a failure in a probabilistic structure replays.

**Measured** (JMH, 1 fork, 3x1s warmup, 3x1s measurement, 8192 random 16-byte
keys, 100-byte values, single thread, on a laptop — indicative, not publishable):

| benchmark | ours | ConcurrentSkipListMap |
|---|---|---|
| insert (per key) | 2.63 ops/us | 2.79 ops/us |
| get, key present | 2.97 ops/us | 3.66 ops/us |
| get, key absent | 2.82 ops/us | 3.58 ops/us |
| full ordered scan (per key) | 172 ops/us | 457 ops/us |

**We lose, and the reason is memory layout, not algorithm.** Each of our nodes
holds its tower as a separate `Node[]` object, so following one pointer is two
dereferences: node, then its array, then the slot. Two objects to pull into cache
per hop instead of one. The JDK's structure uses dedicated index nodes with
direct fields, so a hop is one dereference, and Doug Lea has had a decade to tune
the layout. Our scan also allocates an `Entry` record per step.

Insert is a statistical tie (error bars overlap). The scan gap, 2.7x, is the one
that is unambiguous, and it is the operation a flush performs.

**Deliberately not fixed yet.** Flattening the tower, or iterating without
allocating, are real optimisations with real numbers behind them now. They belong
after the engine works end to end, and the benchmark exists to prove any change
helps. Using the JDK's map instead would have been the fastest path and would
have taught nothing: the point of writing this was to be able to explain the gap,
and the explanation is the deliverable.

## 2026-10-05 — 1.1 follow-up: the allocation hypothesis was wrong

**Claim being tested.** The previous entry blamed the scan gap on two things:
per-entry allocation, and our two-dereference node layout.

**Allocation: ruled out, by measurement.** JMH's `gc` profiler reports
`gc.alloc.rate.norm` of roughly 10^-6 B/op for *every* scan variant, including
the ones that construct an `Entry` per step. Escape analysis deletes the record
outright, because it never leaves the loop; the JDK's `SimpleImmutableEntry`
gets the same treatment. The "no allocation" variants therefore measure call
shape (visitor callback versus iterator), not allocation. The earlier entry's
claim about scan allocating is **wrong** and this corrects it.

**Layout: supported but not proven.** Added `scanFlatBaseline`, a bare singly
linked list over the same keys, where `next` is a direct field rather than an
array slot. Within a single run the ordering is consistent:
flat linked list and ConcurrentSkipListMap are close to each other, and our skip
list is several times slower than both. That points at per-node indirection
(node -> Node[] -> slot, so two objects per hop) rather than the algorithm.

**The honest caveat, which matters more than the result.** Absolute numbers moved
by 7x across runs on this laptop: our scan measured 153 ops/us under the Gradle
plugin and 20 ops/us running the same jar directly minutes later, with tight
error bars inside each run. Something environmental dominates (CPU frequency and
power state, background load, repeated JMH runs heating the machine). So only
**within-run** comparisons mean anything here, and no absolute figure from this
machine belongs in a write-up without a methodology note.

**Two process fixes applied.**
- A `jmh` block in build.gradle.kts: 2 forks, 5 warmup and 5 measurement
  iterations, the gc profiler always on, and `-Pbench=<regex>` to narrow a run.
  One fork and three iterations is a smoke test, not a measurement.
- Found and fixed a real benchmark bug: two `@Setup(Level.Trial)` methods, whose
  order JMH does not guarantee, so the second could run before the data it
  needed existed. Benchmarks need the same scrutiny as production code.

**Open question, parked deliberately.** Whether flattening the tower (fixed-size
node arrays, or an arena of primitives) closes the gap. It needs a quiet machine
and a real experiment, and it is worth nothing until the engine works end to end.
Noted for milestone 1.9, where there will also be an fsync to compare against.
