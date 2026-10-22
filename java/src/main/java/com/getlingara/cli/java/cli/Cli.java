package com.getlingara.cli.java.cli;

import com.getlingara.cli.java.core.Fail;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;

/**
 * Parsing argv against the picocli tree, and the framework defaults overridden in this one place
 * (ADR 16.10.26af D9): {@code -V} stays the root's, every other command gets only {@code
 * -h/--help}, a parse error is {@code usage} (2) and reaches no server, and a group reached alone
 * is {@code usage} too.
 */
public final class Cli {
    private Cli() {}

    /**
     * What argv asked for: help or version text to print (and exit {@code ok}), or a verb with
     * the {@code --json} and {@code --profile} it was given at any depth.
     */
    public record Parsed(String text, Verb verb, boolean json, String profile) {}

    /** The whole tree, every subcommand with its {@code --help}. A fresh model per run. */
    public static CommandLine build() {
        CommandLine root = new CommandLine(new Root());
        Set<CommandLine> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CommandLine sub : root.getSubcommands().values()) {
            addHelp(sub, seen);
        }
        return root;
    }

    private static void addHelp(CommandLine command, Set<CommandLine> seen) {
        if (!seen.add(command)) {
            return;
        }
        command.getCommandSpec().addOption(OptionSpec.builder("-h", "--help").usageHelp(true).description("Show this help").build());
        for (CommandLine sub : command.getSubcommands().values()) {
            addHelp(sub, seen);
        }
    }

    /** Parses {@code argv}; a {@link Fail} with exit {@code usage} when it cannot. */
    public static Parsed parse(List<String> argv) {
        CommandLine root = build();
        ParseResult result;
        try {
            result = root.parseArgs(argv.toArray(String[]::new));
        } catch (ParameterException e) {
            throw Fail.usage(e.getMessage());
        }
        List<ParseResult> chain = new ArrayList<>();
        for (ParseResult at = result; at != null; at = at.subcommand()) {
            chain.add(at);
        }
        if (result.isVersionHelpRequested()) {
            return new Parsed(Root.Version.line() + "\n", null, false, null);
        }
        for (ParseResult at : chain) {
            if (at.isUsageHelpRequested()) {
                return new Parsed(at.commandSpec().commandLine().getUsageMessage(), null, false, null);
            }
        }
        ParseResult leaf = chain.get(chain.size() - 1);
        if (!(leaf.commandSpec().userObject() instanceof Verb verb)) {
            throw Fail.usage(missingSubcommand(leaf.commandSpec()));
        }
        refuseConflicts(leaf, verb);
        return new Parsed(null, verb, matched(chain, "--json") != null, profile(chain));
    }

    private static String missingSubcommand(CommandSpec group) {
        return "'" + group.qualifiedName(" ") + "' requires a subcommand";
    }

    /** Every pair the verb refuses together, both given: {@code usage}, before any request. */
    private static void refuseConflicts(ParseResult leaf, Verb verb) {
        for (String[] pair : verb.conflicts()) {
            if (leaf.hasMatchedOption(pair[0]) && leaf.hasMatchedOption(pair[1])) {
                throw Fail.usage("the argument '" + pair[0] + "' cannot be used with '" + pair[1] + "'");
            }
        }
    }

    /** The innermost level that matched {@code name}, since inherited options match where given. */
    private static ParseResult matched(List<ParseResult> chain, String name) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            if (chain.get(i).hasMatchedOption(name)) {
                return chain.get(i);
            }
        }
        return null;
    }

    private static String profile(List<ParseResult> chain) {
        ParseResult at = matched(chain, "--profile");
        return at == null ? null : at.matchedOptionValue("--profile", (String) null);
    }
}
