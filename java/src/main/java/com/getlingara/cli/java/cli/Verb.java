package com.getlingara.cli.java.cli;

import com.getlingara.cli.java.Ctx;
import java.util.List;

/**
 * A leaf command: what picocli parsed into, run against the context. A group has no verb, so
 * reaching one alone is {@code usage}, as a missing subcommand is in every kit.
 */
public interface Verb {
    void run(Ctx ctx);

    /**
     * Pairs of long flags this command refuses together (the schema's {@code conflicts}), checked
     * after parsing and before any request. picocli's argument groups cannot say "one flag against
     * a set" without restructuring the tree, so the pairs are listed, as clap's {@code
     * conflicts_with} lists them in the reference kit.
     */
    default List<String[]> conflicts() {
        return List.of();
    }
}
