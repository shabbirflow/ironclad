package io.github.shabbirflow.ironclad.maelstrom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IN ONE LINE: drives the client with fake pipes made of strings, so every
 * protocol mistake is caught in milliseconds instead of during a Maelstrom run.
 *
 * The client reads from a Reader and writes to a Writer, so the test hands it a
 * StringReader (pretending to be stdin) and a StringWriter (pretending to be
 * stdout). No process, no pipes, and the failure message names the wrong field.
 */
class MaelstromClientTest {

    private static final String INIT =
            "{\"src\":\"c0\",\"dest\":\"n1\",\"body\":{\"type\":\"init\",\"msg_id\":1,"
                    + "\"node_id\":\"n1\",\"node_ids\":[\"n1\",\"n2\",\"n3\"]}}";

    private final ObjectMapper mapper = MaelstromClient.newMapper();

    /** Catches: addresses not swapped, missing in_reply_to, snake_case mapping. */
    @Test
    void answersInitWithInitOkAddressedBackToTheSender() throws Exception {
        List<String> replies = replyLinesFor(INIT);

        assertThat(replies).hasSize(1);
        Message reply = mapper.readValue(replies.get(0), Message.class);
        assertThat(reply.src()).isEqualTo("n1");
        assertThat(reply.dest()).isEqualTo("c0");
        assertThat(reply.body()).isInstanceOfSatisfying(Body.InitOk.class,
                ok -> assertThat(ok.inReplyTo()).isEqualTo(1L));
    }

    /** Catches: payload dropped or altered, wrong in_reply_to, reply sent to the wrong client. */
    @Test
    void echoesThePayloadBackUnchanged() throws Exception {
        List<String> replies = replyLinesFor(INIT, """
                {"src":"c2","dest":"n1","body":{"type":"echo","msg_id":7,"echo":"Please echo 35"}}""");

        assertThat(replies).hasSize(2);
        Message reply = mapper.readValue(replies.get(1), Message.class);
        assertThat(reply.dest()).isEqualTo("c2");
        assertThat(reply.body()).isInstanceOfSatisfying(Body.EchoOk.class, ok -> {
            assertThat(ok.inReplyTo()).isEqualTo(7L);
            assertThat(ok.echo()).isEqualTo("Please echo 35");
        });
    }

    /** Catches: the node dying on a type we have not implemented. */
    @Test
    void unknownTypeBecomesAnErrorRatherThanACrash() throws Exception {
        List<String> replies = replyLinesFor("""
                {"src":"c1","dest":"n1","body":{"type":"read","msg_id":4,"key":"x"}}""");

        assertThat(replies).hasSize(1);
        Message reply = mapper.readValue(replies.get(0), Message.class);
        assertThat(reply.body()).isInstanceOfSatisfying(Body.Error.class, err -> {
            assertThat(err.code()).isEqualTo(10);
            assertThat(err.inReplyTo()).isEqualTo(4L);
        });
    }

    /** Catches: replying to a reply, which would ping-pong between two nodes forever. */
    @Test
    void repliesAreNotThemselvesRepliedTo() throws Exception {
        assertThat(replyLinesFor("""
                {"src":"n2","dest":"n1","body":{"type":"echo_ok","msg_id":9,"in_reply_to":2,"echo":"hi"}}"""))
                .isEmpty();
    }

    /** Catches: pretty-printing, and newLine() emitting \r\n on Windows. */
    @Test
    void everyMessageIsExactlyOneLineEndingInANewline() throws Exception {
        String stdout = rawStdoutFor(INIT);

        assertThat(stdout).endsWith("\n").doesNotContain("\r");
        assertThat(stdout.chars().filter(c -> c == '\n').count()).isEqualTo(1);
    }

    /** Catches: a garbled line killing the run instead of being skipped. */
    @Test
    void unparseableLineIsSkippedAndTheLoopCarriesOn() throws Exception {
        List<String> replies = replyLinesFor("this is not json", INIT);

        assertThat(replies).hasSize(1);
    }

    /**
     * Runs a whole node against the given lines and returns everything it wrote.
     *
     * String... means "any number of strings": the test passes one line or
     * several, and they are joined with newlines into one block of fake stdin.
     * The client stops when the StringReader runs out, exactly as it stops when
     * Maelstrom closes the real pipe.
     */
    private String rawStdoutFor(String... stdinLines) throws IOException {
        StringReader fakeStdin = new StringReader(String.join("\n", stdinLines));
        StringWriter fakeStdout = new StringWriter();
        PrintStream quietLog = new PrintStream(OutputStream.nullOutputStream());

        new MaelstromClient(fakeStdin, fakeStdout, quietLog, new Node()).run();

        return fakeStdout.toString();
    }

    /** The same output, split into one entry per reply line. */
    private List<String> replyLinesFor(String... stdinLines) throws IOException {
        return rawStdoutFor(stdinLines).lines().toList();
    }
}
