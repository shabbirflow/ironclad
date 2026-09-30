package io.github.shabbirflow.ironclad;

import io.github.shabbirflow.ironclad.maelstrom.MaelstromClient;
import io.github.shabbirflow.ironclad.maelstrom.Node;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Wires the client to the real pipes. UTF-8 is stated explicitly rather than
 * left to the platform default, because JSON is UTF-8 and a Windows default of
 * windows-1252 would mangle any non-ASCII payload.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        MaelstromClient client = new MaelstromClient(
                new InputStreamReader(System.in, UTF_8),
                new OutputStreamWriter(System.out, UTF_8),
                System.err,
                new Node());
        client.run();
    }
}
