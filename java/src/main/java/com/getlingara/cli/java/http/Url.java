package com.getlingara.cli.java.http;

import com.getlingara.cli.java.core.Pair;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Percent-encoding both ways, spelled once so every request encodes alike. */
public final class Url {
    private static final String UNRESERVED = "-._~";

    private Url() {}

    /** RFC 3986 unreserved characters pass; every other byte is {@code %XX}. */
    public static String encode(String text) {
        StringBuilder out = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            boolean plain = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (plain || UNRESERVED.indexOf(c) >= 0) {
                out.append(c);
            } else {
                out.append(String.format("%%%02X", b & 0xff));
            }
        }
        return out.toString();
    }

    /** {@code k=v&k=v}, each side encoded. */
    public static String form(List<Pair> pairs) {
        List<String> parts = new ArrayList<>();
        pairs.forEach(p -> parts.add(encode(p.key()) + "=" + encode(p.value())));
        return String.join("&", parts);
    }

    /** {@code application/x-www-form-urlencoded} decoding: {@code +} is a space, {@code %XX} a byte. */
    public static String decode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < bytes.length; i++) {
            int hex = i + 2 < bytes.length ? hex(bytes[i + 1], bytes[i + 2]) : -1;
            if (bytes[i] == '%' && hex >= 0) {
                out.write(hex);
                i += 2;
            } else {
                out.write(bytes[i] == '+' ? ' ' : bytes[i]);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int hex(byte high, byte low) {
        int h = Character.digit(high, 16);
        int l = Character.digit(low, 16);
        return h < 0 || l < 0 ? -1 : h * 16 + l;
    }

    /** A query string's pairs, decoded, in order. */
    public static List<Pair> parseQuery(String query) {
        List<Pair> out = new ArrayList<>();
        for (String part : query.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String key = eq < 0 ? part : part.substring(0, eq);
            String value = eq < 0 ? "" : part.substring(eq + 1);
            out.add(new Pair(decode(key), decode(value)));
        }
        return out;
    }
}
