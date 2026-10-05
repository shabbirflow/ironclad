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
