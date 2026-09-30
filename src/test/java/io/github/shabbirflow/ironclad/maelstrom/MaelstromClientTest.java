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
 * The client reads from a Reader and writes to a Writer, so a test can drive it
 * with strings. No process, no pipes, no Maelstrom: milliseconds instead of
 * minutes, and the failure names the field that is wrong.
 */
class MaelstromClientTest {

    private static final String INIT =
            "{\"src\":\"c0\",\"dest\":\"n1\",\"body\":{\"type\":\"init\",\"msg_id\":1,"
                    + "\"node_id\":\"n1\",\"node_ids\":[\"n1\",\"n2\",\"n3\"]}}";

    private final ObjectMapper mapper = MaelstromClient.newMapper();

    /** Catches: addresses not swapped, missing in_reply_to, snake_case mapping. */
    @Test
    void answersInitWithInitOkAddressedBackToTheSender() throws Exception {
        List<String> out = lines(INIT);

        assertThat(out).hasSize(1);
        Message reply = mapper.readValue(out.get(0), Message.class);
        assertThat(reply.src()).isEqualTo("n1");
        assertThat(reply.dest()).isEqualTo("c0");
        assertThat(reply.body()).isInstanceOfSatisfying(Body.InitOk.class,
                ok -> assertThat(ok.inReplyTo()).isEqualTo(1L));
    }

    /** Catches: payload dropped or altered, wrong in_reply_to, reply sent to the wrong client. */
    @Test
    void echoesThePayloadBackUnchanged() throws Exception {
        List<String> out = lines(INIT, """
                {"src":"c2","dest":"n1","body":{"type":"echo","msg_id":7,"echo":"Please echo 35"}}""");

        assertThat(out).hasSize(2);
        Message reply = mapper.readValue(out.get(1), Message.class);
        assertThat(reply.dest()).isEqualTo("c2");
        assertThat(reply.body()).isInstanceOfSatisfying(Body.EchoOk.class, ok -> {
            assertThat(ok.inReplyTo()).isEqualTo(7L);
            assertThat(ok.echo()).isEqualTo("Please echo 35");
        });
    }

    /** Catches: the node dying on a type we have not implemented. */
    @Test
    void unknownTypeBecomesAnErrorRatherThanACrash() throws Exception {
        List<String> out = lines("""
                {"src":"c1","dest":"n1","body":{"type":"read","msg_id":4,"key":"x"}}""");

        assertThat(out).hasSize(1);
        Message reply = mapper.readValue(out.get(0), Message.class);
        assertThat(reply.body()).isInstanceOfSatisfying(Body.Error.class, err -> {
            assertThat(err.code()).isEqualTo(10);
            assertThat(err.inReplyTo()).isEqualTo(4L);
        });
    }

    /** Catches: replying to a reply, which would ping-pong between two nodes forever. */
    @Test
    void repliesAreNotThemselvesRepliedTo() throws Exception {
        assertThat(lines("""
                {"src":"n2","dest":"n1","body":{"type":"echo_ok","msg_id":9,"in_reply_to":2,"echo":"hi"}}"""))
                .isEmpty();
    }

    /** Catches: pretty-printing, and newLine() emitting \r\n on Windows. */
    @Test
    void everyMessageIsExactlyOneLineEndingInANewline() throws Exception {
        String raw = raw(INIT);

        assertThat(raw).endsWith("\n").doesNotContain("\r");
        assertThat(raw.chars().filter(c -> c == '\n').count()).isEqualTo(1);
    }

    /** Catches: a garbled line killing the run instead of being skipped. */
    @Test
    void unparseableLineIsSkippedAndTheLoopCarriesOn() throws Exception {
        List<String> out = lines("this is not json", INIT);

        assertThat(out).hasSize(1);
    }

    private String raw(String... inputLines) throws IOException {
        StringWriter out = new StringWriter();
        PrintStream quiet = new PrintStream(OutputStream.nullOutputStream());
        new MaelstromClient(new StringReader(String.join("\n", inputLines)), out, quiet, new Node()).run();
        return out.toString();
    }

    private List<String> lines(String... inputLines) throws IOException {
        return raw(inputLines).lines().toList();
    }
}
