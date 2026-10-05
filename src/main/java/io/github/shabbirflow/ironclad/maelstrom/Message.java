package io.github.shabbirflow.ironclad.maelstrom;

/**
 * IN ONE LINE: one message on the wire, the address plus the letter.
 *
 * The envelope. Maelstrom routes on src and dest alone; everything we care
 * about is in the body. A reply is this same shape with src and dest swapped.
 */
public record Message(String src, String dest, Body body) {

    /** A reply to this message, addressed back to whoever sent it. */
    public Message replyWith(Body replyBody) {
        return new Message(dest, src, replyBody);
    }
}
