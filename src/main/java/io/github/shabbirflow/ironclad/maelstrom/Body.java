package io.github.shabbirflow.ironclad.maelstrom;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * IN ONE LINE: every message type we understand, one record each.
 *
 * One Maelstrom message body. Its shape depends entirely on "type", so each
 * type is its own record and Jackson chooses which one to build from that field.
 *
 * Because the interface is sealed and every permitted record is nested here, a
 * switch over a Body is checked for exhaustiveness at compile time: forget a
 * case and the build fails rather than the cluster.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type",
        defaultImpl = Body.Unknown.class)
@JsonSubTypes({
        @JsonSubTypes.Type(value = Body.Init.class, name = "init"),
        @JsonSubTypes.Type(value = Body.InitOk.class, name = "init_ok"),
        @JsonSubTypes.Type(value = Body.Echo.class, name = "echo"),
        @JsonSubTypes.Type(value = Body.EchoOk.class, name = "echo_ok"),
        @JsonSubTypes.Type(value = Body.Error.class, name = "error"),
})
public sealed interface Body {

    /**
     * The sender's own reference number. Null on bodies that don't carry one.
     * A reply quotes it back in inReplyTo, which is what pairs the two.
     */
    Long msgId();

    /** First message of every run: this is your name, and these are your peers. */
    record Init(Long msgId, String nodeId, List<String> nodeIds) implements Body {}

    record InitOk(Long msgId, Long inReplyTo) implements Body {}

    record Echo(Long msgId, String echo) implements Body {}

    record EchoOk(Long msgId, Long inReplyTo, String echo) implements Body {}

    /** code 10 = not supported, 11 = temporarily unavailable, 0 = timeout. */
    record Error(Long msgId, Long inReplyTo, int code, String text) implements Body {}

    /**
     * Any type we haven't modelled. Jackson falls back to this instead of
     * throwing, so an unexpected message costs us one error reply, not the run.
     */
    record Unknown(Long msgId) implements Body {}
}
