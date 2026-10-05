package io.github.shabbirflow.ironclad.maelstrom;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.io.Writer;
import java.util.Optional;

/**
 * IN ONE LINE: the mouth and ears of a node. It speaks Maelstrom's protocol of
 * JSON lines on stdin and stdout, and knows nothing about databases.
 *
 * The receptionist. Reads one line, turns it into a Message, asks the Node what
 * to answer, writes one line back. It knows nothing about databases.
 *
 * It takes a Reader and a Writer rather than touching System.in and System.out
 * directly, so a test can drive it with strings.
 *
 * Invariant: every request read produces exactly one complete, flushed JSON line
 * on the writer, addressed to its sender with in_reply_to set to that request's
 * msg_id, and nothing else is ever written there.
 */
public final class MaelstromClient {

    private final BufferedReader in;
    private final Writer out;
    private final PrintStream log;
    private final Node node;
    private final ObjectMapper mapper = newMapper();

    public MaelstromClient(Reader in, Writer out, PrintStream log, Node node) {
        this.in = new BufferedReader(in);
        this.out = out;
        this.log = log;
        this.node = node;
    }

    /**
     * SNAKE_CASE maps msgId to msg_id in both directions, so no field needs an
     * annotation. NON_NULL leaves absent fields out instead of writing nulls.
     * FAIL_ON_UNKNOWN_PROPERTIES off, because Maelstrom sends fields we have not
     * modelled and the default is to throw. Pretty-printing stays off: one
     * message must be one line.
     */
    public static ObjectMapper newMapper() {
        return JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /** Runs until stdin closes, which is Maelstrom's way of saying the test is over. */
    public void run() throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            Message request;
            try {
                request = mapper.readValue(line, Message.class);
            } catch (JsonProcessingException e) {
                // Never fatal: one bad line must not end the run. Note it on stderr,
                // which Maelstrom files away per node, and keep going.
                log.println("ignoring unparseable line: " + e.getOriginalMessage());
                continue;
            }
            Optional<Body> reply = node.handle(request.body());
            if (reply.isPresent()) {
                send(request.replyWith(reply.get()));
            }
        }
    }

    private void send(Message message) throws IOException {
        out.write(mapper.writeValueAsString(message));
        // '\n' literally, never newLine(): that uses the platform separator, which
        // is \r\n on Windows, and a stray \r inside the stream breaks framing.
        out.write('\n');
        // Without this flush the reply sits in the buffer and Maelstrom times out.
        out.flush();
    }
}
