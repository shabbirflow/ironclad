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
