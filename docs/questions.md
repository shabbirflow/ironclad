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

## Varint order: which end goes first?

**Ironclad now writes the high group first**, so a varint reads left to right like
the big-endian `crc32c` and `length` fields. One record, one direction.

```
69420 = 4*16384 + 30*128 + 44     base-128 digits, high to low: 4, 30, 44
on disk: 84 9E 2C                 =  4, 30, 44     high group leads
```

Encoding: ask `varintBytes` how many groups there are, then emit them from the
top, setting the continuation bit on all but the last.
Decoding: `result = (result << 7) | group`, stopping at the first byte whose top
bit is clear. No shift counter needed.

It was originally low-group-first, which is what Protobuf, LevelDB and RocksDB
do, and which needs no pre-pass to encode. We switched because the byte count was
already being computed to size the buffer, so high-first costs nothing and the
record stops carrying two byte orders. SQLite, MIDI and ASN.1 are high-first too.

Earlier I claimed the low end "has to" lead and that SQLite agreed. Both wrong.

`getVarint` accumulates into a `long` so that a value too large for an `int` can
be rejected; in an `int` that overflow would be silent.

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

## The header indices: what is at 0, 1 and 4?

Byte indices, not field numbers, and `putInt` writes four bytes because an int is
four bytes.

```
index:  0  1  2  3 | 4  5  6  7 | 8     9     10..16   17     18..24
        \--crc32c--/ \--length--/ kind  keyLen  key    valLen  value
                                  \------------ payload -------/
```

For key "user:42" (7 bytes) and value "shabbir" (7 bytes), payloadBytes = 17 and
the record is 25 bytes on disk.

- `buffer.putInt(4, payloadBytes)` writes four bytes at indices 4..7, the length
  field, storing 17.
- `buffer.putInt(0, crc)` writes four bytes at indices 0..3, the crc field.
- `crc.update(buffer.array(), 4, 4 + payloadBytes)` is
  `update(array, offset, length)`: **start** at byte 4, hash **21 bytes**, which
  covers bytes 4..24, the length field plus the payload. The two numbers look
  alike but one is an offset and the other is a count.

## Would an empty value become a delete?

No, and that is exactly what `emptyKeyAndEmptyValueSurvive` guards.

| | kind byte | value section |
|---|---|---|
| `Put(key, new byte[0])` | 1 | present, length 0 |
| `Delete(key)` | 2 | absent entirely |

The bug it prevents: deciding the kind with something like
`value == null || value.length == 0`, or treating a value length of 0 during
decode as "no value here". Either way an empty value would come back as a
delete, and replay would **remove** the key instead of storing an empty value.

Storing an empty value is legitimate: a key whose presence is the information, a
JSON empty string, a cleared field. It has to stay distinguishable from a delete.

## What is 16_384 with the underscore?

A Java numeric literal separator, available since Java 7. The compiler ignores
the underscores: `16_384 == 16384`. It is only for human eyes, like writing
`1_000_000` or `0xFF_FF_FF_FF`. It cannot lead, trail, or sit beside the decimal
point.

## Does decode recompute the checksum and compare?

Yes, exactly that:

```java
CRC32C crc = new CRC32C();
crc.update(source.slice(start + 4, 4 + length));   // length field + payload
if ((int) crc.getValue() != expectedCrc) { ... }   // vs the 4 bytes at index 0
```

Recompute from the bytes actually on disk, compare with the value the writer
stored in the header. Different means the bytes are not what was written:
flipped by a failing drive, or a payload that never finished being written while
its header did.

`getValue()` returns a `long` holding an unsigned 32-bit value, and only four
bytes were stored, so both sides are narrowed to `int` the same way. The bit
patterns match even though the signed interpretation might be negative.


## Does a new ByteBuffer start at position 0?

Yes. `allocate(n)` gives position 0, limit = capacity = n, and zero-filled bytes.
`wrap(array)` likewise: position 0, limit = array length.

The cursor through `encode`, for a 7-byte key and 7-byte value:

```
allocate(25)      pos=0   lim=25     fresh, all zeros
position(8)       pos=8              skip past the header
put(kind)         pos=9
putVarint(7)      pos=10             key length
put(key)          pos=17
putVarint(7)      pos=18             value length
put(value)        pos=25             full
putInt(4, len)    pos=25  <-         absolute: bytes 4..7, cursor unmoved
putInt(0, crc)    pos=25  <-         absolute: bytes 0..3, cursor unmoved
flip()            pos=0   lim=25     readable from the start
```

Which shows why the header is written last: a checksum cannot cover bytes that do
not exist yet. And it is why absolute puts are needed, since with relative puts
the header would land at position 25, past the end.

## What does the CI actually do, and why bother?

Mechanically: on every push, GitHub starts a **fresh Ubuntu machine**, clones the
repo, installs JDK 21, runs `./gradlew test` and `jmhClasses`, and marks the
commit pass or fail. About a minute, free for public repos.

Three reasons it earns its place:

1. **It answers "does this work on a machine that is not mine?"** My laptop has a
   JDK at a known path, a warm Gradle daemon, Windows line endings, IntelliJ
   settings. A clean Linux box has none of that. It already caught one real
   failure: git had `gradlew` without the execute bit, so `./gradlew` on Linux
   would have failed with "Permission denied" for anyone who cloned the repo.
2. **It catches regressions once the project is too big to re-run by hand.** In
   Phase 3, a Raft change that breaks a milestone 1.1 ordering test gets flagged a
   minute after the push instead of weeks later.
3. **Credibility.** A green badge says the tests exist and pass from a clean
   checkout. Plenty of GitHub projects do not even compile for a stranger.

What it deliberately skips: Maelstrom runs (an install, minutes per run) and
benchmark *execution* (shared runners have noisy neighbours, so the numbers would
be fiction). It compiles benchmarks so a broken one still fails fast.

Could a solo project skip CI? Yes, but it cost 20 minutes to add, gets expensive
to retrofit, and is the cheapest credibility in the project.

## Does "kernel buffer space exhausted" mean my RAM runs out?

Not RAM as a whole: the kernel's allowance for **dirty pages**, bytes accepted
from programs but not yet written to the device. Linux caps it. Copy a 4 GB file
to a slow USB stick and the progress bar flies to 30% then crawls: the fast part
filled the page cache, the crawl is the kernel refusing more until the stick
catches up.

For a regular file on a blocking channel that backpressure makes `write()`
**block** rather than return a short count. Short writes really come from pipes,
sockets, non-blocking descriptors, or a signal arriving mid-call. We still loop,
because `FileChannel.write` only promises "the number of bytes written" and
relying on behaviour an API does not guarantee is how a bug appears once, in
production, on a kernel you do not own.

## If Ironclad is distributed, why fail-stop on one node?

Both levels are true at once:

```
one node's disk fails
  - that node:    fail-stop. Stops accepting writes. Does not lie.
  - the cluster:  carries on. Four others hold the data and keep serving.
```

And Raft **requires** it. Consensus works because "yes, I have it" is a promise.
A follower that acknowledges an entry it could not persist can lose it on
restart, and a leader elected on the strength of that acknowledgement can then
lose a committed write. A node that cannot persist must stop promising.

Option B (carry on) is right only when something else holds the truth, or when
the data is disposable: caches, metrics, access logs. Redis loses up to a second
of writes by default, on purpose.

## Streaming versus whole-log replay

Whole-log: read the entire file into one buffer, decode across it. Simple, one
meaning for `Incomplete`, but peak memory is log plus memtable plus records, and
it breaks on a log bigger than expected, which is exactly when recovery can least
afford an OutOfMemoryError.

Streaming: read a chunk (1 MB), decode records out of it, apply each immediately,
then read the next. Memory is one chunk plus one record whatever the size. The
cost is that a record can straddle a chunk boundary, so leftover bytes must be
carried forward with `compact()`, and `Incomplete` now means either "chunk ended"
or "log ended" - only end-of-file tells them apart.

Chose streaming: it survives milestone 1.3 unchanged and it is the same technique
the SSTable reader needs.

## What does "no rotation" mean?

Rotation means: when the log gets big, close it and start `wal-000002.log`. Then
when a memtable flush makes old records redundant, whole files get deleted.

It exists because you **cannot cheaply remove bytes from the front of a file** -
there is no such operation. So "forget the first 64 MB" is only possible if that
part is a separate file. Needed at 1.3, when flushing finally creates something
to delete. Until then: one file, growing.

## force(false) versus force(true)?

The flag is `metaData`. `true` also flushes file metadata such as timestamps.
`false` flushes the data plus whatever metadata is needed to **find** that data,
which includes the file's length. So `false` is cheaper by one metadata write and
still correct for an append-only log. Truncation uses `force(true)`, because
there the file length *is* the thing being changed.

## What are "separate channels for recovery"?

A channel is opened with a fixed set of permissions. Recovery needs READ to scan
and WRITE to truncate the torn tail. Appending wants APPEND, where every write
goes to the end automatically. So recovery opens its channel, scans, truncates,
closes; then the writer opens its own append-only channel. Two phases, each with
the least permission it needs, and no mode switching in the middle.

## Should Incomplete be split into "chunk ended" and "log ended"?

No, not in the codec. The codec only sees the bytes in front of it, so it has no
evidence for which one it is; inventing the distinction there would be a lie.

The **reader** knows whether the file had more bytes, so the distinction lives in
its `RecoveryResult.StopReason`: CLEAN_END, TORN_TAIL, CORRUPT. General rule
worth keeping: each layer reports only what it can actually see, and the layer
with the extra knowledge adds the interpretation.


## Is the log several files? What if the other half of a record is in the next one?

One file. A "chunk" is only how much of that one file is pulled into the buffer
at a time.

```
ONE file:  | rec1  rec2  rec3   rec4   rec5 |
reads:     |--#1--||--#2--||--#3--|
```

A record straddling a boundary is split across two **reads of the same file**:
the bytes are all there, just not fetched yet, and compact() plus the next read
stitches them. Nothing is missing.

When the **file** is exhausted and leftover bytes do not form a whole record,
there is nowhere the rest could be: it was never written, because the power died
mid-write. That is what makes it a torn tail rather than a boundary.

In milestone 1.3 there will be several segment files, but rotation always happens
**on a record boundary**, so a record never spans two files. That rule exists for
exactly this reason.

## endOfFile && buffer.hasRemaining(): what does that combination mean?

In read mode `hasRemaining()` means position < limit: unread bytes are in the
buffer.

| endOfFile | hasRemaining | meaning |
|---|---|---|
| false | - | refill the buffer; normal, happens every chunk or two |
| true | false | CLEAN_END: the last record ended exactly as the file did |
| true | true | TORN_TAIL: leftover bytes and no more file to complete them |

So "the buffer has content but it is the end of the file" means a half-written
record whose other half does not exist anywhere.

## What is Path?

`java.nio.file.Path`, Java's type for a filesystem location and the modern
replacement for `File`. `Path path` is a parameter of that type:
`Path.of("C:/Users/shabb/dev/ironclad/ironclad.wal")`. Modern file APIs (`Files`,
`FileChannel.open`) take a Path rather than a String, so separators and relative
paths are handled instead of being string-concatenated.

## Why is it RecoveryResult.StopReason.CLEAN_END?

`StopReason` is an enum **nested inside** the `RecoveryResult` record, so its full
name is Outer.Inner. CLEAN_END is the optimistic default: if the loop finishes
without anything going wrong, the log ended cleanly, and the failure paths
overwrite it.

## Is there one buffer of chunk size?

Yes. `ByteBuffer.allocate(chunkBytes)` once per replay, reused for every chunk,
1 MB by default and 17 bytes in the boundary tests. It changes size only if a
single record turns out to be bigger than it.

## Where is apply defined?

At the bottom of WalReader, and it is the whole bridge from bytes to state:

```java
private static void apply(WalRecord record, SkipList memtable) {
    switch (record) {
        case WalRecord.Put put -> memtable.put(put.key(), put.value());
        case WalRecord.Delete delete -> memtable.remove(delete.key());
    }
}
```

## "Remember the failure forever" - until when exactly?

`durabilityFailure` is a field on the WalWriter object and nothing clears it, so
"forever" means as long as that object lives, which in practice is until the
process restarts. Recovery does not un-poison it: recovery happens in a fresh
process with a brand-new writer, and the poisoned one died with the old JVM. The
point is not that the failure is eternal, it is that nothing in this run can talk
you out of it.

## grow(): the Math.min, full.capacity(), and bigger.put(full.flip())

```java
int cap  = WalCodec.HEADER_BYTES + WalCodec.MAX_RECORD_BYTES;  // biggest legal record
int size = Math.min(cap, full.capacity() * 2);                 // double, never past that
ByteBuffer bigger = ByteBuffer.allocate(size);
bigger.put(full.flip());
```

`full` is the parameter name: the buffer that arrived with no room left.
`full.capacity()` is its current size. Doubling keeps growth steps few; the
ceiling stops a corrupted length field from walking us up to gigabytes.

`buffer = grow(buffer)` reassigns the variable and nothing shrinks it, so the
buffer stays big for the rest of that replay call. A later replay starts fresh at
the chunk size.

`bigger.put(full.flip())` is two steps. After compact(), `full` is in write mode:
position = leftover count, limit = capacity. `flip()` makes it readable (position
0, limit = leftover count), and `put` copies those bytes into `bigger`, leaving
bigger's position at the leftover count. So `bigger` ends up in exactly the state
compact() produces, with a larger tray.

## Why force(true) after truncate but force(false) after append?

Appending flushes data plus the metadata needed to find it, which is what
force(false) does. Truncating writes no data at all: its entire effect **is** the
file's length, which is metadata. Rather than reasoning about whether the cheap
flush covers a shrinking file, ask for metadata explicitly. If that length change
were lost in a crash, the torn tail would come back and recovery would stop at the
same byte again.

## When does the log get emptied?

Today, never. The only trimming is the torn tail; good records stay forever, so
the file grows without bound and recovery gets slower every restart.

Milestone 1.3 fixes it: once the memtable is flushed to an SSTable, the records
that produced it are redundant, because that data is now on disk in sorted form.
Then whole segment files get deleted. Now recorded in docs/deferred.md.

## What happens to the `stop` variable?

It is a local that accumulates the reason and becomes part of the return value:

```java
RecoveryResult.StopReason stop = CLEAN_END;   // optimistic default
...
stop = CORRUPT;   detail = corrupt.reason();
stop = TORN_TAIL; detail = "N bytes of a half-written record";
...
return new RecoveryResult(applied, validBytes, stop, detail);
```

Callers care because the three mean different things operationally: CLEAN_END is
a normal shutdown and says nothing; TORN_TAIL is routine after a crash and
deserves one log line, since nobody was promised that record; CORRUPT means a
checksum failed on data that **was** fully written, which is a disk going bad and
worth shouting about. That is why it is not a boolean.

## In grow(), is `size <= full.capacity()` the same as `cap <= full.capacity()`?

Yes. `full.capacity() * 2` is always greater than `full.capacity()`, so
`Math.min(cap, capacity * 2)` can only come out at or below the capacity when
`cap` is the smaller one. Rewritten to check the ceiling directly, because it
states the intent: "are we already as big as the format allows?"

The throw is unreachable while decode rejects any length above MAX_RECORD_BYTES,
since no legal record can need more room than HEADER + MAX_RECORD. It exists so
that raising the limit later fails loudly instead of spinning in the caller's
loop.

## flipByte(firstRecordBytes + HEADER_BYTES + 1): why that byte?

Pointer arithmetic onto a chosen byte. Record one is Put("a","1") = 13 bytes:
8 header plus 5 payload (kind, key length, key, value length, value).

```
byte:  0 ----------- 12 | 13 ---- 20 | 21 | 22 | ...
       |- record one ---| |-crc+len-| kind keyLen     <- record two
        firstRecordBytes   HEADER_BYTES  +1
```

- firstRecordBytes steps over record one
- + HEADER_BYTES steps over record two's crc and length
- + 1 steps over the kind byte, landing inside record two's **payload**

Payload rather than header on purpose: flipping a payload byte can only produce
"checksum mismatch", which is what the test asserts. Flipping a length byte might
trip the "not a real record size" check instead, so the test would be asserting a
different failure than its name claims.

The point of the test: three records, the second damaged, the **third perfectly
intact**, and only `a=1` survives. Record three is deliberately destroyed, because
applying it without record two would build a state that never existed.

## theWriterRefusesEverythingAfterADurabilityFailure, explained

```java
FileChannel channel = FileChannel.open(log(), CREATE, WRITE, APPEND);
WalWriter writer = new WalWriter(channel);          // package-private constructor
writer.append(put("a","1"));                        // fine
channel.close();                                     // the disk "goes away"

assertThatExceptionOfType(IOException.class)         // 1st append after: real failure
        .isThrownBy(() -> writer.append(put("b","2")));
assertThat(writer.isStopped()).isTrue();

assertThatExceptionOfType(IllegalStateException.class)   // 2nd: refused untried
        .isThrownBy(() -> writer.append(put("c","3")))
        .withMessageContaining("durability failure");
```

Closing the channel stands in for a dying disk: any write or force on a closed
channel throws ClosedChannelException, which is an IOException, the same category
force() throws when the disk is full or gone. Deterministic and close enough.

The package-private constructor exists for this. `WalWriter.open(path)` owns its
own channel, so a test could never reach in to break it; a test in the same
package can hand in a channel it controls.

Two different exception types on purpose:
- IOException: the write really failed, so the caller must not acknowledge it.
- IllegalStateException: we did not even try. The writer is poisoned and the call
  was invalid given its state.

`withMessageContaining("durability failure")` pins that the message explains why,
because someone reading a log at 3 a.m. needs to know the writer stopped on
purpose. `isStopped()` is the observable flag a Phase 3 node would use to remove
itself from the cluster instead of voting on entries it cannot persist.

What the test catches: remove the poisoning and the third append merely hits the
closed channel again, throwing ClosedChannelException, so the IllegalStateException
assertion fails. The test pins the remembering, not just the failing.

## How does the entriesInBlock > 0 guard actually work?

```java
if (entriesInBlock > 0 && blockBytes + entryBytes > 4096) finishBlock();
appendEntry(entry);     // unconditional
```

| arriving entry | entries > 0? | would exceed? | seal? | block after |
|---|---|---|---|---|
| A (120 B) | no (0) | - | no | 120 B, 1 entry |
| B (200 B) | yes | no | no | 320 B, 2 |
| ... | | | | 4000 B, 30 |
| Z (150 B) | yes | 4150 > 4096 yes | seal | 150 B, 1 |
| BIG (1 MB) | yes | yes | seal | **1 MB, 1 entry** |

On the last row the guard fires, Z's block is sealed, counters reset to 0 and 0,
and then `appendEntry` runs unconditionally, so BIG lands in an empty block that
ends up 1 MB. Nothing checks it again until the next entry arrives, and the index
records each block's real length, so no reader assumes 4 KB.

Without the guard: block empty, `0 + 1MB > 4096` is true, seal an empty block,
block still empty, true again, forever. Sealing never makes room because BIG is
bigger than the target regardless. The guard breaks the cycle: an empty block
cannot be sealed, so the oversized entry has to land somewhere.

## On delete, do we edit the original record in its SSTable?

Never. The delete goes into the **memtable**, in RAM, exactly like any other
write, and later gets flushed into a **new** SSTable. An old file is never opened
for writing again.

Four reasons editing in place is wrong:
- you would have to find it first, searching every file on every delete
- it is a random write, the exact thing LSM exists to avoid
- someone may be reading that file right now, or it is memory-mapped
- a crash mid-edit corrupts a file that was previously safe forever

So a delete is a write: same path as a put, with kind = 2 and no value. Today
SkipList.remove unlinks physically, which is fine because nothing is on disk yet;
milestone 1.5 is where the memtable starts storing a tombstone entry instead.

## Is a block the 4 KB thing?

Yes. An **entry** is one key/value pair (kind, lengths, bytes), tens to hundreds
of bytes. A **block** is a run of entries stopped at about 4 KB, plus a 4-byte
checksum. "About", because an entry is never split across blocks, so a block ends
up 3,980 or 4,090 bytes, or 1 MB in the oversized case. The index records each
block's exact length.

## What are the index and the footer here?

A table of contents and a signpost pointing at it.

**Index**: one row per **block**, not per key.

```
block 0  first key "apple"    offset 0      length 4088
block 1  first key "ferret"   offset 4088   length 4012
block 2  first key "mango"    offset 8100   length 4096
```

Looking for "kiwi": binary search those rows, it is >= "ferret" and < "mango", so
if it exists it is in block 1. Read bytes 4088..8100, one disk read, then scan
about 100 entries in memory. The index cannot say whether a key exists, only
which 4 KB to look in; that is what "sparse" means, and it is why 1,000,000 keys
need only 10,000 rows.

**Footer**: the last 32 bytes, fixed size, holding where the index starts, its
length, the entry count, a format version and a magic number. The index sits
after the data because its contents are unknown until every block is written, so
a reader needs a known place to find out where it went. Like a book whose table
of contents is printed at the back, with the last page saying "contents begin on
page 412".

Opening a file: seek to fileSize - 32, read the footer, check the magic, read the
index, binary search it, read one block, scan it. Two small reads to open, then
one read per lookup, whatever the file's size.

## What does "300 becomes 0x82 0x2C" actually mean?

Varints carry seven bits per byte, so split 300 into seven-bit groups:

```
300 = 100101100          nine bits, so two groups
low seven bits: 0101100 = 44
what remains:        10 = 2        check: 2 * 128 + 44 = 300
```

300 is "2 then 44" in base 128. High group first:

```
group 2  -> 0000010 -> continuation bit set -> 1000 0010 = 0x82   more follows
group 44 -> 0101100 -> bit clear            -> 0010 1100 = 0x2C   last byte
```

Reading back: 0x82 has the top bit set so result = 2; 0x2C does not, so
result = (2 << 7) | 44 = 300. `<< 7` is multiplying by 128: it shifts the digits
already collected one place left in base 128 to make room, exactly like reading
"47" as (4 * 10) + 7.

## Why does getVarint stop after 5 bytes?

An int is 32 bits and each varint byte carries 7. Four bytes hold 28 bits, which
is not enough; five hold 35, which is. So no legal int varint exceeds five bytes.
If five go by without a byte whose top bit is clear, these are not a varint but
corruption or a misaligned read, and -1 says so. Same instinct as the 16 MB
record cap: never let corrupt data set a loop bound.

## Checksums per block, not per entry or per file?

Per block, and the rule is **checksum the unit you read**.

| per | cost | why not |
|---|---|---|
| entry | 4 bytes on a 60-byte entry, 7% bigger file, thousands of CRCs per lookup | overhead on every byte and every read |
| block | 4 bytes per 4 KB, 0.1%, one CRC per lookup | this is the one |
| file | 4 bytes total | you would read all 64 MB to verify one 4 KB block |

The WAL checksums per **record** instead, because it is written incrementally and
a crash can tear any single record: there the unit of risk is the record. Same
tool, different failure model.

## The footer fields, one by one

```
[ indexOffset : 8 ][ indexLength : 4 ][ entryCount : 8 ][ version : 4 ][ magic : 8 ] = 32
```

- **indexOffset** (long): byte position where the index block starts, different in
  every file, which is the whole reason the footer exists. A long because files
  can exceed 2 GB and an int offset would wrap silently.
- **indexLength** (int): how many bytes to read from there. An int is plenty; the
  index is a few hundred KB even for a million keys.
- **entryCount** (long): key/value pairs in the **whole file**, not index rows. The
  index row count is just the block count, which comes from reading the index.
  Used for bloom filter sizing (1.6), merge planning (1.7), and a cheap size().
- **version** (int): 1 today, so a future reader refuses loudly instead of
  misparsing.
- **magic** (8 bytes): ASCII IRONCLAD. Identity, plus completeness because it is
  written last.

## SsTableInfo fields

entryCount = key/value pairs written. blockCount = how many data blocks they
filled. fileBytes = the file's total size in bytes. firstKey and lastKey = the
key range, used in 1.5 to skip a file whose range excludes the key, and in 1.7 to
decide which files overlap and must be merged together.

## Why 0x7F everywhere in the varint code?

`0x7F` is `0111 1111`: the low **seven** bits, all ones, with the eighth bit
zero. Seven because a varint byte spends its eighth bit on the continuation flag
("another byte follows"), leaving seven for the number itself.

So the two idioms read as:
- `value & 0x7F` -> keep only the seven bits that fit in this byte
- `value & ~0x7F` -> everything **above** those seven bits, so `!= 0` means
  "there is more number left"
- `sevenBits | 0x80` -> switch the flag on, since `0x80` is `1000 0000`

0x7F is the payload mask and 0x80 is the flag.

## Does the block ByteBuffer "grow"?

A ByteBuffer's capacity is fixed when it is allocated, so growing means
**allocating a bigger one and copying** what is already there, which is what
`ensureRoom` does.

Normally it never happens: the buffer starts at 4096 + 1024 bytes, and blocks are
sealed at 4096, so there is always slack for one more ordinary entry. The only
trigger is a single entry larger than the target, such as a 50 KB value, which is
legal and gets a block to itself.

Two different numbers worth keeping apart: **4096 is the sealing target** (when to
start a new block) and **5120 is the buffer's capacity** (how much it can
physically hold before reallocating).

## What does Varints.put(block, key.length) do in append()?

Writes the key's **length**, so the reader knows how many bytes of key follow
before the value starts. Same two lines again for the value. Nothing else in the
entry says where one field ends and the next begins.

## Is there one index entry per block?

Yes, exactly one:

```java
index.add(new IndexEntry(firstKeyInBlock, fileOffset, length));
fileOffset += length;
```

- `firstKeyInBlock` answers "could the key I want be in this block?"
- `fileOffset` is bytes written so far, so it is where this block **starts**
- `length` is how many bytes to read

Then `fileOffset += length` moves the running total past this block, so it is
already correct for the next one. That one variable serves as both "size of the
file so far" and "offset of whatever comes next".

Blocks are written in key order, so the index rows are in key order too, which is
what makes binary searching them legal.

## What does writeIndex() do?

Turns the list of index rows into the index block on disk, and returns its size
so the footer can record it.

1. **Measure.** Walk the rows adding up varint key length + key + 8-byte offset +
   4-byte length, plus 4 for the checksum. Measuring first means allocating
   exactly once, with no growing and no guessing.
2. **Fill.** Walk the rows again writing those fields. `putLong` for the offset
   because a file can exceed 2 GB; `putInt` for the length because one block
   never can.
3. **Checksum.** CRC32C over everything written, appended at the end, exactly like
   a data block: the index is read as a unit, and a corrupt index sends every
   lookup to the wrong bytes.
4. **Write and account.** Write it fully, advance `fileOffset`, return the byte
   count for the footer's `indexLength`.

## Is 0x7F the continuation check?

No, that is 0x80. Keep them apart:

- `0x80` = `1000 0000` = **the flag**. `(b & 0x80) != 0` asks "is another byte
  coming?"
- `0x7F` = `0111 1111` = **the payload mask**. `b & 0x7F` strips the flag off and
  keeps the seven bits of actual number.

0x80 asks the question; 0x7F takes the data.

## Why is the block buffer 4096 + 1024?

Two different numbers:

- **4096 is the sealing target**: when to stop filling a block.
- **5120 is the buffer's capacity**: how much it can physically hold.

The slack exists for two reasons. First, a sealed block is entries **plus a
4-byte checksum**, so a block whose entries reach 4096 needs 4100. Second, an
entry slightly larger than the target gets a block to itself, and anything up to
about 5116 bytes fits without reallocating.

It is a performance detail, not correctness: `ensureRoom` grows the buffer for any
entry of any size. The slack just means that almost never happens.

## What is an "index row", and why is file order also key order?

A row is one line of the table of contents, one per block:

```
row 0:  first key "apple"    offset 0       length 4088
row 1:  first key "ferret"   offset 4088    length 4012
row 2:  first key "mango"    offset 8100    length 4096
```

`index` is the list of those rows, built as blocks are sealed.

File order is key order because keys arrive sorted and blocks are written in the
order they fill. So block 0 holds the smallest keys, block 1 the next, and so on,
which is what makes binary searching the rows legal.

## In the index row, what is the offset, and what are the 8 and the 4?

The **offset** is the byte position in the file where that block starts, so the
reader can seek straight to it.

The 8 and 4 are **how many bytes those fields take**: `putLong(offset)` is 8
bytes because a file can exceed 2 GB, and `putInt(length)` is 4 because a single
block never can. They appear in the measuring pass because the buffer has to be
sized before anything is written into it.

## "writeIndex returns bytes but its return type is private int"

Three separate things in one line: `private` is visibility, `int` is the return
type, and `bytes` is just the name of a local variable holding a number of bytes.
`return bytes;` returns that int, which the caller stores as `indexLength` and
writes into the footer.

## Why do add() and append() both exist?

`add` and `addTombstone` are the public API: the two different things a caller can
record. `append` is the private implementation both call, with a different kind
byte.

Without it, the ordering check, the size calculation, the sealing rule and the
writing would exist twice, and a fix to one copy would miss the other.

## Is there a whole-file checksum as well as the block ones?

No. The checksums are: **one per data block**, and **one for the index block**.
Nothing covers the whole file, because verifying one 4 KB read would then mean
reading all 64 MB.

The footer has no checksum either, which is a real gap: corruption there is
caught only by the magic bytes or by offsets that turn out to be implausible.
Recorded in docs/deferred.md.

## When is close() used?

`close()` closes the file channel, and it is called automatically by
try-with-resources: `try (SsTableWriter writer = SsTableWriter.create(path))`
calls it at the end of the block, including when something throws.

It is not the same as `finish()`. `finish` writes the index and footer and forces
the file to disk, making it a valid SSTable; `close` only releases the operating
system's file handle. Closing without finishing leaves a file with no magic at the
end, which is exactly what an interrupted flush looks like, and such a file is
deleted and rebuilt from the log.

