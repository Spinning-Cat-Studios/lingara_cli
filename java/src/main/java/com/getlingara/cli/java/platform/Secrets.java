package com.getlingara.cli.java.platform;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Map;

/**
 * 16.10.26ae D4's {@code platform_toml_secret}: the first value or key, in file order, that looks
 * like a secret. A value is one when it matches {@code lgr_[a-z]+_[A-Za-z0-9]{8,}} and is not a
 * public client id ({@code lgr_cid_…}); a key is one when its {@code _}-separated words include
 * {@code secret}, {@code token} or {@code password}, case-insensitively ({@code CLIENT_SECRET},
 * {@code api_token}; never {@code TOKENIZER}). The refusal names the key, never the value.
 */
public final class Secrets {
    private static final String[] SECRET_WORDS = {"secret", "token", "password"};

    private Secrets() {}

    /** Whether {@code s} holds a real-looking {@code lgr_} token other than a client id. */
    public static boolean secretValue(String s) {
        for (int at = s.indexOf("lgr_"); at >= 0; at = s.indexOf("lgr_", at + 4)) {
            if (tokenAt(s, at + 4)) {
                return true;
            }
        }
        return false;
    }

    /** {@code [a-z]+_[A-Za-z0-9]{8,}} from {@code kind}, its kind not {@code cid}. */
    private static boolean tokenAt(String s, int kind) {
        int kindEnd = kind;
        while (kindEnd < s.length() && s.charAt(kindEnd) >= 'a' && s.charAt(kindEnd) <= 'z') {
            kindEnd++;
        }
        boolean shaped = kindEnd > kind && kindEnd < s.length() && s.charAt(kindEnd) == '_';
        return shaped && bodyLength(s, kindEnd + 1) >= 8 && !s.substring(kind, kindEnd).equals("cid");
    }

    private static int bodyLength(String s, int from) {
        int end = from;
        while (end < s.length() && s.charAt(end) < 128 && Character.isLetterOrDigit(s.charAt(end))) {
            end++;
        }
        return end - from;
    }

    public static boolean secretKey(String key) {
        for (String word : key.split("_", -1)) {
            for (String secret : SECRET_WORDS) {
                if (word.equalsIgnoreCase(secret)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The dotted path of the first secret-shaped key or value, if any; {@code null} otherwise. */
    public static String firstSecret(JsonNode value) {
        return walk(value, "");
    }

    private static String walk(JsonNode value, String at) {
        if (value.isTextual()) {
            return secretValue(value.asText()) ? at : null;
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                String found = walk(item, at);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> member = it.next();
            String path = at.isEmpty() ? member.getKey() : at + "." + member.getKey();
            String found = secretKey(member.getKey()) ? path : walk(member.getValue(), path);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
