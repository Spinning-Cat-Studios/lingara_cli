package com.getlingara.cli.java.http;

import java.io.IOException;
import java.util.List;

/**
 * The one seam every request crosses: {@code java.net.http} in production, a scripted fake in the
 * unit tests. Everything above it is synchronous, so the commands read as plain sequences.
 */
public final class Wire {
    private Wire() {}

    /** One request on the wire, already spelled. */
    public record Out(String method, String url, List<String[]> headers, byte[] body) {}

    /** One answer: status, headers as sent, body. */
    public record In(int status, List<String[]> headers, byte[] body) {
        public String header(String name) {
            for (String[] h : headers) {
                if (h[0].equalsIgnoreCase(name)) {
                    return h[1];
                }
            }
            return null;
        }
    }

    /** A transport error is a connection failure: contract B2 rule 7. */
    public interface Transport {
        In send(Out request) throws IOException;
    }

    /** The time a run reads and waits on, so tests never sleep. */
    public interface Clock {
        long now();

        void sleep(long secs);
    }

    public static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long now() {
            return System.currentTimeMillis() / 1000;
        }

        @Override
        public void sleep(long secs) {
            try {
                Thread.sleep(secs * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    };
}
