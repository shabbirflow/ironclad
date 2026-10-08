# Questions I asked, and the short answers

Quick reference for revision. Newest at the bottom.

Lesson pages:
- Lesson 1, processes and pipes: https://claude.ai/code/artifact/b1227c3b-378f-48a2-a180-5b2fd17eecc3
- Lesson 2, classes to a command: https://claude.ai/artifact/LzroLt1uyYh8ZxgJG7J5uV

---

## Is Maelstrom for testing nodes, or client-to-server, or server-to-server?

All of it, and it is a test harness, not a communication library. Think JUnit
for distributed systems, except it also cuts the network and audits the result.

Under Maelstrom your program has no port and no sockets. It is a process that
reads JSON lines from stdin and writes them to stdout. Maelstrom plays two
roles at once:
- **The clients**: fake users c1, c2 ... that send requests like read and write.
- **The network**: every node-to-node message also passes through it, so it can
  delay, reorder or drop any of them.

So all three flows exist (client to node, node to node, node to client) and all
use the same JSON envelope on the same two pipes.

Consequence for Ironclad: the real database will use TCP sockets between nodes
(milestone 3.1), but under Maelstrom there are no sockets. So the Raft logic must
sit behind a transport interface with two implementations: Maelstrom
(stdin/stdout) for testing, TCP for running it.

## What does a "vote request" mean?

Raft, Phase 3. Five nodes hold copies of the data, and exactly one, the leader,
may accept writes. Two leaders at once means conflicting writes: "split brain".

When the leader goes quiet, a node becomes a candidate and sends every other
node a **vote request**: "I am n1, this is election round 4, my data is this up
to date, will you vote for me?" The reply is yes or no.

Two details make it work:
- **Term** (the round number): a vote counts only for the round it was cast in,
  so stale elections cannot interfere.
- **Majority, not everyone** (3 of 5): any two majorities share a member, and
  that member refuses to vote twice in a round, so two leaders cannot both be
  elected. Requiring everyone would mean one offline node blocks all progress.

The pizza version: five friends, one order. If two of them phone the restaurant,
two pizzas arrive and someone pays twice.

## What is Jackson?

Java's standard JSON library. It turns JSON text into objects and back. Already
familiar: Spring Boot uses Jackson whenever a controller returns an object.

```java
Message in = mapper.readValue(line, Message.class);   // text   -> object
String out = mapper.writeValueAsString(reply);        // object -> text
```

The three jars in `lib/` are one library split by job:
- `jackson-core`: low-level JSON reader and writer.
- `jackson-databind`: `ObjectMapper`, maps JSON onto classes. What we use.
- `jackson-annotations`: annotations that tweak that mapping.

Two settings we will need: never pretty-print (one message must be one line, or
newline framing breaks), and ignore unknown fields (Maelstrom sends fields we do
not model, and Jackson throws by default).

Allowed by the dependency rule because it is only for the Maelstrom protocol.
Writing a JSON parser would teach parsing, which is not what Ironclad is for.

## In the generated launcher, what are APP_HOME and CLASSPATH?

Shell variables, nothing to do with Java or Gradle. `NAME=value` stores one,
`$NAME` reads it.

**CLASSPATH** (line 117 of `build/install/ironclad/bin/ironclad`) is where the
JVM looks for classes, entries separated by `:` on Linux and `;` on Windows. It
is `PATH` for classes: the shell searches PATH to find `git`, the JVM searches
the classpath to find `ObjectMapper.class`. Gradle writes out all four jars
explicitly and regenerates the line on every build.

**APP_HOME** (line 89) is the install folder, found by taking the script's own
path and going up out of `bin/`. It matters because Maelstrom runs the script
from Maelstrom's own working directory, so a relative `lib/...` path would look
in the wrong place. The script gives directions from where it stands.

The last lines: `JAVACMD` is `$JAVA_HOME/bin/java`, or plain `java` from PATH,
which is why WSL needs a JDK 21+ installed. `exec "$JAVACMD" "$@"` replaces the
shell process with the JVM (same PID), so Maelstrom's pipes and kill signals hit
the JVM directly with no shell wrapper left to orphan it.

## Option A (application plugin) vs Option B (fat jar): which is faster?

Effectively identical where it matters, so A stays.

- **Per message, at runtime: no difference.** Once the JVM is warm, the classes
  are loaded and how they were packaged is irrelevant. This is the only number
  that affects a Maelstrom run, because nodes start once and then handle
  thousands of messages.
- **Startup: a few milliseconds apart.** A fat jar opens one zip instead of
  four. Against a JVM start of a few hundred milliseconds, that is noise. The
  launcher script itself costs ~1 ms and then disappears via `exec`.
- **Build time: A wins.** installDist copies files, incrementally. A fat jar
  re-zips every dependency whenever they change.
- **The real startup cost is neither.** Reading jars across `/mnt/c` from WSL is
  far slower than any packaging difference. If node startup ever matters, copy
  `build/install/ironclad` into the Linux filesystem, or look at AppCDS. Do not
  reach for a fat jar to fix it.

## Lesson 3 check answers, in one line each

**Why can't a node get its name as a command-line argument instead of via init?**
Every node is launched by the same script with no per-node arguments, so identity
has to arrive in-band, and `init` is the only channel there is.

**A node replies to two requests in the wrong order. Why doesn't that break?**
Replies are matched by `in_reply_to`, not by arrival order, so the client tracks
its own outstanding msg_ids and does not care which comes back first. (Not
because nodes avoid talking to each other: they do talk, via Maelstrom.)

**Write sent to followers, nothing heard before the deadline. Which error code?**
Code 0, timeout, which is indefinite: the write may still commit a second later.
A definite error would promise it never happened, and that becomes a
linearizability violation the moment the value shows up.

## What is "the client" in "write a Maelstrom client"?

Confusing word, because my program is a *node*, i.e. a server. "Client" here
means client of Maelstrom's protocol, in the same sense as "HTTP client" or
"Kafka client": the code that knows how to speak someone else's message format.

Its whole job: read a line from stdin, turn the text into an object, hand it to
the right handler, turn the answer back into text, write exactly one line and
flush. It is a receptionist, and it knows nothing about databases.

```
Maelstrom  <-> stdin/stdout <->  [ protocol client ]  ->  my logic
                                   (0.3, ~150 lines)      (echo now, Raft later)
```

In Phase 3 the same class becomes one implementation of the transport interface,
with TCP sockets as the other.

## What is a record?

A one-line immutable data class. Java generates the constructor, the accessors,
equals, hashCode and toString from the header. Built into the language since 16.

```java
record Echo(Long msgId, String echo) implements Body {}
```
replaces roughly 40 lines of POJO, or Lombok's @Value. Accessors are named after
the component: `e.echo()`, not `e.getEcho()`. Fields are final, so there are no
setters. Good for messages, because a message that arrived is a fact, and no
code path should be able to rewrite it half way through handling.

## What is a sealed interface?

A normal interface can be implemented by anyone, anywhere, so the compiler can
never know the full set of implementors. A **sealed** interface lists exactly
which types may implement it (explicitly with `permits`, or implicitly when they
are all nested in the same file).

The payoff: a `switch` over it is checked for exhaustiveness. Forget a case and
the build fails. An enum is a fixed set of *values*; a sealed interface is a
fixed set of *shapes*, each carrying its own fields.

Old habit, with no safety net:
```java
if (body instanceof Echo) { ... } else if (body instanceof Init) { ... }
```
New version, where the compiler is on your side:
```java
Body reply = switch (body) {
    case Body.Init init -> initOk(init);
    case Body.Echo echo -> echoOk(echo);
    // miss a case -> "the switch statement does not cover all possible input values"
};
```
The pattern `case Body.Echo echo` also binds an already-typed variable, so there
is no cast.

## Where is replyWith used?

In the read-reply loop, once, at the moment of writing the reply:
```java
Message request = mapper.readValue(line, Message.class);
Body reply = switch (request.body()) { ... };
out.write(mapper.writeValueAsString(request.replyWith(reply)));
```
It exists so that the src/dest swap is written in exactly one place, instead of
at every reply site where it could be forgotten.

## Sealed interface, the monkey version

```java
public sealed interface Monkey {
    record Capuchin(int tinyHats)      implements Monkey {}
    record Howler(int decibels)        implements Monkey {}
    record Mandrill(String faceColour) implements Monkey {}
}

String feed(Monkey m) {
    return switch (m) {                       // no default branch needed
        case Monkey.Capuchin c -> "banana, plus " + c.tinyHats() + " tiny hats";
        case Monkey.Howler h   -> "mango, and earplugs at " + h.decibels() + " dB";
        case Monkey.Mandrill d -> "figs, and compliment the " + d.faceColour();
    };
}
```

Three monkeys on the enclosure sign, and nobody can smuggle in a fourth. Add
`record Gibbon(int armSpan)` and the build breaks with "the switch expression
does not cover all possible input values": I cannot ship until I have said what
a gibbon eats. Without `sealed`, someone in another package writes
`class RobotMonkey implements Monkey` and my switch either explodes at runtime or
silently feeds it bananas.

Monkey is Body. Capuchin/Howler/Mandrill are Init/Echo/Error. feed() is the
message handler. In Phase 3, adding RequestVote will march me to every place that
handles messages. An enum cannot do this: enums are a fixed set of values, all
the same shape; a sealed interface is a fixed set of shapes with their own fields.

## What is Node, and is it the database server or a Maelstrom client?

Node is the **brain of one node**: what this participant knows (its name, its
peers, its msg_id counter) and what it decides to answer. Right now all it knows
is how to echo. In Phase 1 the memtable, WAL and SSTables move in here; in Phase
3, the Raft state.

The split is deliberate:
- `MaelstromClient` = mouth and ears. Speaks the protocol. Knows no databases.
- `Node` = brain. Decides answers. Knows nothing about pipes or JSON.

That line is why Phase 3 can swap the mouth for TCP sockets without touching the
brain.

## Does every node have its own MaelstromClient?

Yes. One node = one OS process = one JVM = one `Main` = one `MaelstromClient`
plus one `Node`. Five nodes means five of each, with five separate memories.
Nothing is shared; Maelstrom is the only thing passing messages between them.

## What does `yield` do?

It supplies the value of a `switch` branch when that branch is a block with
several statements. A single-expression arrow branch needs no `yield`.

```java
int x = switch (day) {
    case MON -> 1;                                  // expression: no yield
    case TUE -> { log("tuesday"); yield 2; }        // block: must yield a value
};
```
`return` would leave the whole method; `yield` only produces the switch's value.
In `Node.handle` the init branch is a block (store nodeId, store nodeIds, then
answer), so it ends in `yield`.

## What is an ObjectMapper?

Jackson's translator between Java objects and JSON text. Two methods in use:
- `readValue(text, Message.class)`: JSON text -> Java object
- `writeValueAsString(object)`: Java object -> JSON text

The builder settings only tune how it translates: snake_case field names,
leave out null fields, do not throw on unknown fields, no pretty-printing.

## `mapper.readValue(line, Message.class)` — is that string to JSON?

The other way round. The line **already is** JSON: a string of JSON text that
arrived on stdin. `readValue` parses that text into a Java object graph, a
`Message` holding a `Body`. Writing goes back the other way.

## What is Optional?

A box holding either one value or nothing. The alternative is returning `null`,
which compiles fine and then throws NullPointerException somewhere else at 2 a.m.
`Optional<Body>` says in the type system: there may be no reply.

- `reply.isPresent()` -> is there anything in the box?
- `reply.get()` -> take it out

`init` and `echo` need replies. `echo_ok` does not, so `handle` returns
`Optional.empty()` for it.

## The four lines in run(), with real values

```
line    = {"src":"c2","dest":"n1","body":{"type":"echo","msg_id":7,"echo":"hi"}}
request = Message[src=c2, dest=n1, body=Echo[msgId=7, echo=hi]]
reply   = Optional[EchoOk[msgId=1, inReplyTo=7, echo=hi]]
request.replyWith(reply.get())
        = Message[src=n1, dest=c2, body=EchoOk[msgId=1, inReplyTo=7, echo=hi]]
send(...) writes
  {"src":"n1","dest":"c2","body":{"type":"echo_ok","msg_id":1,"in_reply_to":7,"echo":"hi"}}
followed by '\n', then flush.
```
In `send(Message message)`, `message` is the parameter: the reply built on the
line above.

## Where does the line of text come from?

From Maelstrom, through the stdin pipe, via three wrappers:

```
Maelstrom (in WSL) writes a line into the pipe
        |
        v
System.in              raw bytes (fd 0)
InputStreamReader      bytes -> characters, decoded as UTF-8
BufferedReader         characters -> lines, split on '\n'
        |
        v
in.readLine()          one JSON message as a String
```

`Main` builds the first two and hands them to the client; the client's
constructor wraps them in a `BufferedReader`. That is the only reason
`readLine()` exists: `System.in` has no idea what a line is.

`readLine()` **blocks**: the thread parks there, using no CPU, until a line
arrives. It returns null when Maelstrom closes the pipe, which is how the run
ends and the process exits.

In the tests the same line comes from a `StringReader` instead. Identical
interface, no process, no pipes, which is exactly why the client takes a `Reader`
rather than reaching for `System.in` itself.

## What are JMH, a benchmark, and a "harness"?

**Harness**: the rig you strap your code into so something else can run and
measure it. Maelstrom is a *test* harness (runs my nodes, checks the history).
JMH is a *benchmark* harness (runs one method, times it honestly).

**JMH** = Java Microbenchmark Harness, from the OpenJDK team. Timing a loop with
`System.nanoTime` lies, because the JIT compiler attacks the benchmark:
- *dead code elimination*: a result never used means the call is deleted, and an
  empty loop reports a million ops/sec
- *warmup*: the first runs are interpreted, then compiled, so early numbers
  measure the compiler
- *constant folding*: same input every time can be computed once and reused

JMH forks a fresh JVM, runs warmup iterations before measuring, feeds results
into a `Blackhole` so nothing can be optimised away, and reports a distribution.

First result, parsing one Maelstrom message: **2.14 ops/us**, about 2.1 million
messages a second. That is the protocol's own overhead, and it is nowhere near
being the bottleneck.

## Is CI just automating the tests?

Yes. On every push, a fresh Ubuntu machine checks out the repo, installs JDK 21,
runs `./gradlew test`, and marks the commit pass or fail. Maelstrom runs stay
local: they need Maelstrom installed and take minutes.

One real gotcha found while setting it up: git had `gradlew` recorded as mode
100644, with no execute bit, because Windows has no such bit. On the Linux runner
`./gradlew` would have failed with "Permission denied". Fixed with
`git update-index --chmod=+x gradlew`. Same lesson as Maelstrom's launcher script.

## Is Blackhole.consume there so the JIT cannot delete the work?

Yes. A benchmark whose result is never used is dead code, and the JIT deletes
dead code, leaving an empty loop that reports a fabulous number.
`Blackhole.consume` is a sink the JVM cannot see through, so the parse counts as
used and has to actually happen.

Shortcut worth knowing: returning the value from the `@Benchmark` method does the
same thing, because JMH consumes whatever is returned. `Blackhole` is for when
there are several results, or no natural single return value.

## In the test, where does the output list come from?

From a helper method at the bottom of the test file, not from the input. The
names were confusing, so they are now `rawStdoutFor` and `replyLinesFor`.

```
INIT, a JSON string
  -> String.join("\n", lines)      one block of fake stdin
  -> new StringReader(...)         pretends to be the stdin pipe
  -> client.run()                  reads until the string runs out
  -> StringWriter                  pretends to be stdout, captures everything
  -> .toString()                   the raw text written
  -> .lines().toList()             List<String>, one entry per reply line
```

`String...` is varargs: "any number of strings", so `replyLinesFor(INIT)` passes
one line and `replyLinesFor(INIT, echo)` passes two.

The client stops when the StringReader runs out, exactly as it stops when
Maelstrom closes the real pipe: `readLine()` returns null either way. That is the
payoff of taking a `Reader` and a `Writer` instead of using System.in directly.

## What is a memtable?

A sorted map living in RAM that holds the most recent writes. That is all it is.

Life of one write:
1. appended to the **WAL** on disk (safety only, read just after a crash)
2. inserted into the **memtable** in RAM (sorted; this is what reads hit)
3. when the memtable hits its size limit, it is frozen, written out as one
   sorted file (an SSTable), and an empty memtable takes over

Reads check the memtable first because it holds the newest data, then the files
newest to oldest. A crash destroys the memtable and that is fine: the WAL has
every write, so startup replays the log and rebuilds it.

Like a desk inbox kept in alphabetical order. You do not walk to the filing
cabinet for each sheet; the tray fills, and because it is already sorted, filing
the stack is one pass with no sorting.

## Does mem = memory and SS = secondary storage?

mem = memory, yes. **SS = "Sorted String"**, not secondary storage. The term is
from Google's Bigtable paper: an SSTable is an immutable file of key-value pairs
sorted by key, where keys and values are byte strings.

memtable = sorted table in memory. SSTable = sorted table in a file.

**Secondary storage** (persistent, block-addressed): NVMe SSD, SATA SSD, HDD,
SD card, USB flash, cloud block storage such as EBS (a disk over a network).
**Primary** storage is RAM, plus CPU caches and registers. Tape is tertiary.

Why it matters: every Phase 1 design choice exists to hide the cost of secondary
storage. Append instead of overwrite, buffer in RAM, write one big sorted file
sequentially instead of many small random writes.

Caveat: the random-write penalty was brutal on spinning disks (physically moving
an arm). On NVMe it is much smaller, but LSM still wins, because flash erases in
large blocks and overwriting one key in place makes the drive copy a whole block
behind your back. That is write amplification, measured in milestone 1.9.

## What is LSM?

Log-Structured Merge tree. Not a tree data structure; an architecture, and it is
exactly what Phase 1 builds.

- **Log-structured**: never modify in place, only append. A new value is written
  in front of the old one rather than over it.
- **Merge**: because old data piles up, a background process merges files and
  drops superseded values. Without it, reads get slower forever.

```
writes -> WAL (append) + memtable (sorted, RAM)        milestones 1.1, 1.2
                          | flush when full
                        SSTable, SSTable, SSTable ...  1.3, 1.4
                          | merge when too many
                        fewer, bigger SSTables         1.7 compaction
reads  -> memtable, then files newest to oldest,
          bloom filters to skip files                  1.5, 1.6
```

The rival family is the **B-tree**, which updates pages in place: find the page,
read it, modify it, write it back, all random I/O.

| | Writes | Reads | Used by |
|---|---|---|---|
| LSM | sequential, fast | slower, several files to check | RocksDB, LevelDB, Cassandra |
| B-tree | random, slower | fast, one place to look | PostgreSQL, InnoDB, SQLite |

An LSM is a diary: always write at the end, occasionally consolidate. A B-tree is
an address book: every entry has its place, so inserting in the middle means
rewriting the page.

The idea behind all of it: optimise **two** of {write cost, read cost, space},
never all three. LSM buys cheap writes and pays in read cost and space.

## Why does an injectable Random mean a failing test can be "replayed"?

`new Random()` seeds itself from the clock, so every run produces different
numbers. In a skip list that means different node heights, so a **different
shape** every run.

Now suppose a bug only shows up for one particular shape. With clock seeding the
test fails maybe once in fifty runs, and when you rerun it to investigate, the
shape is different and it passes. That is a flaky test, and it is almost
impossible to debug.

`new Random(7L)` produces the **same sequence of numbers every time**, so the
same heights, the same shape, the same operations. The test either always passes
or always fails. "Replay" just means: run it again and get the identical
scenario, including in a debugger.

Like a shuffled deck. If you want to study one freak bridge hand, you need the
shuffle to be repeatable, not a fresh shuffle each time.

The large random test seeds two of them: one for the operation sequence, one for
the node heights.

## What was Consumer/action doing in forEach, and what is it now?

It was a for loop over lane 0, nothing more. Every key lives in lane 0, so
walking `head.next[0]` to the end visits everything in order.

`Consumer<Entry>` only meant "a function taking an Entry and returning nothing",
and `action.accept(entry)` called it. The loop lived in the list; the caller
passed in what to do with each entry, which forced callers to write lambdas.

Replaced with `implements Iterable<Entry>`, so iteration is an ordinary loop:

```java
for (SkipList.Entry entry : list) {
    out.write(entry.key());
}
```

`Iterable` also provides `forEach` for free, so the lambda style still works for
anyone who prefers it. Milestone 1.8 needs a real `Iterator` regardless, to merge
several sorted sources.

## Could we just use ConcurrentSkipListMap if ours is slower?

Technically yes, and nothing in the engine would notice. Three reasons not to,
and one that actually settles it.

**It is not the bottleneck, by orders of magnitude.** Even at the worst measured
figure, our scan walks 8192 keys in about 400 microseconds. A single `fsync` in
milestone 1.2 costs 1 to 10 *milliseconds*. The memtable is 3 to 25 times cheaper
than one durability call, and every write needs one of those. Optimising here
while an fsync sits in the same write path is spending effort where it cannot
show up.

**The project exists to teach low-level Java.** The build plan says to write it
anyway and benchmark it: "beating or losing to it, with an explanation of why, is
a better story than using it". An interviewer cannot probe a library call.

**CSLM cannot do what later milestones need.** Byte-accurate size accounting for
flush thresholds, versioned keys in Phase 2 where a newer version of a key must
sort first, and any off-heap or arena layout. We would end up wrapping it and
fighting it.

**And the decision stays reversible.** Program against a small `Memtable`
interface, keep CSLM as the benchmark baseline, and swapping implementations is a
one-line change if the numbers ever demand it.

## What is a codec?

Coder plus decoder: the pair of functions turning an object into bytes and back.

```java
ByteBuffer   encode(WalRecord record)   // object -> bytes for the file
DecodeResult decode(ByteBuffer bytes)   // bytes from the file -> object
```

Same word as in "video codec": H.264 encodes frames to bytes and decodes them
back. Jackson is a JSON codec. Ours is binary, because JSON would be several
times larger and slower, and the WAL is the file written most.

It is a separate class from the file-writing code so the byte layout can be
tested with no files at all, including by handing it **deliberately corrupted
bytes**, which is the whole point of having a checksum.

## Is the "longest contiguous prefix" in the data or in the WAL?

In the WAL file. The log is a list of events in time:

```
r1  put a=1
...
r55 put k=9
r56 ####  torn        <- recovery stops here
r57 delete b          <- discarded even though intact
```

The prefix is records r1..r55, a prefix of the **file**, in append order. Nothing
is truncated in key order: replaying r1..r55 rebuilds the memtable to exactly the
state it had when r55 became durable. The data is **rewound in time**, not
missing a range of keys.

That is the difference between the two: the log holds operations, the memtable
holds state. A prefix of operations always produces a state that really existed.
A subset with a hole in it produces a state that never existed, which is why r57
goes even when its bytes are fine.

## Do we replay the log from first to last? And does that take time?

Yes, in append order, because order is the meaning. `put a=1` then `put a=2`
ends with a=2; replayed backwards it ends with a=1, which is a state that never
existed.

Recovery time is proportional to log size, which is why the log is **bounded**:
once the memtable is flushed to an SSTable, the log records it covers are safe on
disk in sorted form, and that part of the log is deleted. So the log only ever
holds unflushed writes, around one memtable's worth (64 MB), and reading it is a
sequential scan at hundreds of MB/s. Milestone 1.9 measures recovery time against
WAL size for exactly this reason.

## What was the claimed advantage of option B, and what do we checksum?

We checksum **the 4 length bytes plus the payload bytes** (not the CRC field
itself, which would be circular).

The claim was that including the length detects corruption of the header, where
option A (payload only) would not. Mutating the code to option A left all 10
tests passing, because a corrupted length makes the decoder hash the **wrong
range of bytes**, so the checksum fails anyway. So header corruption is caught
either way; B only makes it direct rather than incidental. Marginal, kept because
it costs nothing. LevelDB checksums only its type and data.

## Does Delete change the kind in the payload?

Yes. The payload starts with a kind byte: 1 for put, 2 for delete. A delete
record has kind 2 and **no value section at all** - it stops after the key.

That is different from a put with an empty value, which has kind 1 and a value
length of 0. Both exist and must stay distinguishable.

## "Every truncation from 0 bytes up to one short of complete is Incomplete"

The test encodes one record (say 20 bytes), then tries to decode only the first
0 bytes, then the first 1, then 2, up to 19. Every one must come back
`Incomplete`, never `Corrupt` and never `Ok`.

Why: a crash can stop a write at **any** byte, so every possible cut has to be
recognised as unfinished rather than damaged. Under 8 bytes there is not even a
full header to read; past that the length is known but the payload is short.

## Why must decode not move the buffer's position?

A `ByteBuffer` carries a position, a cursor. Relative reads like `getInt()`
advance it; absolute reads like `getInt(4)` do not. `decode` uses absolute reads
only.

The replay loop owns that cursor and advances it **only on success**, by
`bytesConsumed`. If `decode` consumed bytes and then failed, the cursor would be
left somewhere inside a torn record, and the next read would start at a garbage
offset - possibly finding a plausible-looking record inside the wreckage of a
real one. Leaving the position untouched means a failure leaves the reader
exactly where it was, so truncation happens at the right offset.

## "Sealed, so the switch is checked by the compiler" - what does that mean here?

Not that a Put turns into a Delete. It means: add a **third** kind of record
later (a transaction marker, say) and every exhaustive switch over `WalRecord`
stops compiling until it is handled.

Fair challenge, though: the encoder was using `instanceof`, which gets no such
check, so the comment was overstating things. Changed to an exhaustive switch:

```java
byte[] value = switch (record) {
    case WalRecord.Put put -> put.value();
    case WalRecord.Delete delete -> null;
};
```

Now the claim is true.

## Is big-endian left to right, lowest address first?

Yes. Most significant byte first, at the lowest address. `0x12345678` is stored
`12 34 56 78`. Little-endian stores it `78 56 34 12`.

Why big-endian on disk: it is network byte order, it reads naturally in a hex
dump, and byte-by-byte comparison of two big-endian numbers gives the same answer
as comparing the numbers - useful when keys are sorted bytewise. x86 is
little-endian internally, which is exactly why a file format must state its
choice rather than inherit one. `ByteBuffer` already defaults to big-endian.

## Is 16 * 1024 * 1024 just 16 MB? And what does a private constructor do?

Yes, 16,777,216 bytes. Written as a product so the intent is readable.

`WalCodec` is a utility class: only static methods, no state. The private
constructor means **nothing outside the class can create an instance**, and
nothing inside does either. `new WalCodec()` would be a meaningless object, so
the private constructor documents and enforces that. `final` separately blocks
subclassing.

## What does varintBytes do, and how does the payload size add up?

`varintBytes` **counts** how many bytes the varint form of a number will need. It
writes nothing. We need the count up front to size the buffer.

The arithmetic adds both the length fields and the data:

```java
int payloadBytes = 1                              // kind byte
    + varintBytes(key.length)                     // size of the key-length field
    + key.length                                  // the key bytes
    + (value == null ? 0                          // delete: nothing more
        : varintBytes(value.length) + value.length);  // value length field + bytes
```

For key "user:42" (7 bytes) and value "shabbir" (7 bytes):
1 + 1 + 7 + (1 + 7) = 17 payload bytes, plus the 8-byte header = 25 on disk.

## How does the varint bit arithmetic work?

`0x7F` is `0111 1111`, the low seven bits. `~0x7F` is every bit **above** those.

- `(value & ~0x7F) != 0` asks "are there bits higher than seven, so more bytes
  needed?"
- `(value & 0x7F) | 0x80` takes the low seven bits and sets the top bit as a
  continuation flag: "another byte follows".
- `value >>>= 7` throws away the seven bits just written. `>>>` is the unsigned
  shift, so a negative number does not keep sign-filling ones from the left.

Encoding 300:
```
300            = 1 0010 1100
300 & 0x7F     = 010 1100 = 44     -> write 44 | 0x80 = 0xAC   (more follows)
300 >>> 7      = 2                 -> no high bits left
                                   -> write 0x02               (last byte)
result: AC 02
```
Decoding reverses it: 0xAC has the top bit set, so take 44 at shift 0; 0x02 does
not, so take 2 at shift 7, giving 44 + 256 = 300. Stop.

Ranges: 1 byte holds 0 to 127, 2 bytes to 16,383, 3 to 2,097,151.

## Varint order: for 69420, which end goes first?

The small end. Lab: https://claude.ai/artifact/AtaKudd6Pxbh2Ak4VZie1J

```
69420 = 4*16384 + 30*128 + 44     base-128 digits, high to low: 4, 30, 44
on disk: AC 9E 04                 =  44, 30, 4     low group leads
```

This is the opposite direction from the big-endian `length` field, on purpose:
the number of bytes a varint needs is only known after consuming its low bits, so
the low end has to lead. Protobuf, SQLite and LevelDB all do the same. One record
carries both byte orders, which is why a format must state its conventions.

## Does decode move byte by byte past a corrupt record?

No. `decode` never moves the cursor at all; the replay loop advances it only on
`Ok`, by `bytesConsumed`. On `Corrupt` or `Incomplete` it stops and truncates
there. No scanning forward for the next plausible record: past a hole there may
be a perfectly valid record that must not be applied, because the operation
before it is missing.

## "Sealed, so the switch is checked by the compiler" - concretely

Add a third record kind later:

```java
record TxnCommit(long txnId) implements WalRecord {}
```

and the encoder stops compiling: "the switch expression does not cover all
possible input values". A record kind the writer silently ignores cannot ship.
Nothing turns a Put into a Delete; the guarantee is about future edits.

Contrast inside the same file: `decode` switches over the `kind` **byte**, and a
byte is just a number that nothing seals, so that switch needs a `default` and
gets no help at all.

## Do absolute puts move the buffer cursor?

No. `putInt(4, x)` writes at index 4 and leaves the position alone; `putInt(x)`
writes at the position and advances it by 4. Same split for `getInt(4)` versus
`getInt()`. The header is filled in with absolute puts because the payload was
already written with relative ones, and the checksum cannot be computed until the
payload exists.

## What does s.getBytes(StandardCharsets.UTF_8) do?

Turns a String into bytes. Java holds strings as UTF-16 in memory; this encodes
them as UTF-8 bytes, which is what the codec and the files deal in. It is not our
encoding: UTF-8 is the standard text encoding, and the record format wraps
whatever bytes come out. It appears only in tests, because the codec's API is
byte[] and text is converted at the edge.

The charset is named explicitly for the same reason as in Main: a Windows default
of windows-1252 stores "e-acute" in one byte where UTF-8 uses two, so the key
length and therefore the bytes on disk would differ between machines.
