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
