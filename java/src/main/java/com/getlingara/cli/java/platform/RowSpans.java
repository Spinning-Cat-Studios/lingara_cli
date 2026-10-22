package com.getlingara.cli.java.platform;

import java.util.List;

/**
 * Where each {@code [[apps]]} row sits in a {@code platform.toml}'s lines: from its header up to
 * the next table header that is not its own {@code [apps.env]}, as {@code [start, end)}.
 */
final class RowSpans {
    private RowSpans() {}

    private static boolean header(String line) {
        return line.stripLeading().startsWith("[") && !line.strip().equals("[apps.env]");
    }

    /** The span of the row named {@code name}, or {@code null}. */
    static int[] spanOf(List<String> lines, String name) {
        String wanted = "name = " + Write.quote(name);
        for (int start = 0; start < lines.size(); start++) {
            if (!lines.get(start).strip().equals("[[apps]]")) {
                continue;
            }
            int end = start + 1;
            while (end < lines.size() && !header(lines.get(end))) {
                end++;
            }
            for (int i = start; i < end; i++) {
                if (lines.get(i).strip().equals(wanted)) {
                    return new int[] {start, end};
                }
            }
        }
        return null;
    }

    /** The first line in {@code span} that starts (after indentation) with any of {@code prefixes}; -1 for none. */
    static int find(List<String> lines, int[] span, String... prefixes) {
        for (int i = span[0]; i < span[1]; i++) {
            String line = lines.get(i).stripLeading();
            for (String prefix : prefixes) {
                if (line.startsWith(prefix)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Every line with its LF. */
    static String joined(List<String> lines) {
        StringBuilder out = new StringBuilder();
        lines.forEach(l -> out.append(l).append('\n'));
        return out.toString();
    }
}
