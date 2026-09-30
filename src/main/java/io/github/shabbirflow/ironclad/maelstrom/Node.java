package io.github.shabbirflow.ironclad.maelstrom;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The logic behind the receptionist. In milestone 0.3 it echoes; in Phase 1 the
 * storage engine moves in here, and in Phase 3, Raft.
 *
 * Identity is not a constructor argument: the process is launched with no
 * arguments and learns its own name from the init message.
 */
public final class Node {

    /**
     * Our own reference numbers. AtomicLong rather than long++ because
     * getAndIncrement is one indivisible step: with several threads, long++ is
     * read-modify-write and two threads can walk away with the same number,
     * which would make two messages indistinguishable to a client.
     */
    private final AtomicLong nextMsgId = new AtomicLong(1);

    /**
     * Written by the init handler, read by every later handler. Volatile because
     * once Phase 3 adds timers on other threads, a plain field could be written
     * by one thread and never seen by another. Volatile guarantees that write is
     * visible to whoever reads next.
     */
    private volatile String nodeId;
    private volatile List<String> nodeIds = List.of();

    public String nodeId() {
        return nodeId;
    }

    public List<String> nodeIds() {
        return nodeIds;
    }

    private long nextMsgId() {
        return nextMsgId.getAndIncrement();
    }

    /**
     * The body to reply with, or empty when the message needs no answer.
     *
     * No default branch: Body is sealed, so the compiler confirms all six cases
     * are covered. Add a message type and this switch stops compiling until it
     * is handled.
     */
    public Optional<Body> handle(Body body) {
        return switch (body) {
            case Body.Init init -> {
                nodeId = init.nodeId();
                nodeIds = List.copyOf(init.nodeIds());   // immutable copy: safe to share
                yield Optional.of(new Body.InitOk(nextMsgId(), init.msgId()));
            }
            case Body.Echo echo ->
                    Optional.of(new Body.EchoOk(nextMsgId(), echo.msgId(), echo.echo()));
            case Body.Unknown unknown ->
                    Optional.of(new Body.Error(nextMsgId(), unknown.msgId(), 10, "not supported"));

            // Answers to messages we sent. Replying to a reply would ping-pong forever.
            case Body.InitOk initOk -> Optional.empty();
            case Body.EchoOk echoOk -> Optional.empty();
            case Body.Error error -> Optional.empty();
        };
    }
}
