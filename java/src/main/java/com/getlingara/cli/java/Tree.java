package com.getlingara.cli.java;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.cli.Cli;
import com.getlingara.cli.java.core.Json;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

/**
 * {@code lingara __tree} (ADR 16.10.26ab D4; 16.10.26af D2): the registered tree, read back from
 * picocli's own {@code CommandSpec} model rather than written twice. picocli records everything
 * the projection needs, so there is no kit-local table: a {@code Path} is {@code path}, an
 * {@code Integer} {@code int}, an enum {@code enum} with its completion candidates as values, a
 * {@code boolean} {@code bool}, a multi-value option repeatable. A command is dangerous exactly
 * when it carries {@code --confirm}, the schema's own rule (16.10.26ab D2.4).
 */
public final class Tree {
    private Tree() {}

    public static ObjectNode tree() {
        CommandSpec root = Cli.build().getCommandSpec();
        ObjectNode out = level(root);
        out.put("name", root.name());
        ArrayNode globals = out.putArray("global_flags");
        root.options().stream().filter(Tree::isFlag).forEach(o -> globals.add(param(o)));
        return out;
    }

    /** Each subcommand once: picocli also keys a command under each of its aliases. */
    private static List<CommandSpec> children(CommandSpec spec) {
        List<CommandSpec> out = new ArrayList<>();
        for (var entry : spec.subcommands().entrySet()) {
            CommandSpec child = entry.getValue().getCommandSpec();
            if (entry.getKey().equals(child.name()) && !child.usageMessage().hidden()) {
                out.add(child);
            }
        }
        return out;
    }

    /** A topic's commands and sub-topics: a subcommand with subcommands of its own is a topic. */
    private static ObjectNode level(CommandSpec spec) {
        ObjectNode out = Json.object();
        ArrayNode commands = out.putArray("commands");
        ArrayNode topics = out.putArray("topics");
        for (CommandSpec child : children(spec)) {
            if (child.subcommands().isEmpty()) {
                commands.add(command(child));
            } else {
                ObjectNode topic = level(child);
                topic.put("name", child.name());
                topic.set("aliases", Json.array(List.of(child.aliases())));
                topics.add(topic);
            }
        }
        return out;
    }

    private static ObjectNode command(CommandSpec spec) {
        ObjectNode out = Json.object();
        out.put("name", spec.name());
        out.set("aliases", Json.array(List.of(spec.aliases())));
        out.put("dangerous", spec.findOption("--confirm") != null);
        ArrayNode args = out.putArray("args");
        for (PositionalParamSpec p : spec.positionalParameters()) {
            args.add(positional(p));
        }
        ArrayNode flags = out.putArray("flags");
        spec.options().stream().filter(o -> !o.inherited() && isFlag(o)).forEach(o -> flags.add(param(o)));
        return out;
    }

    private static boolean isFlag(OptionSpec option) {
        return option.longestName().startsWith("--") && !option.usageHelp() && !option.versionHelp();
    }

    private static ObjectNode param(OptionSpec option) {
        ObjectNode out = Json.object();
        out.put("name", option.longestName().substring(2));
        out.put("type", kind(option));
        out.put("required", option.required());
        out.put("repeatable", option.isMultiValue());
        for (String name : option.names()) {
            if (name.length() == 2 && name.charAt(0) == '-') {
                out.put("short", name.substring(1));
            }
        }
        if (element(option).isEnum()) {
            out.set("values", Json.array(values(option.completionCandidates())));
        }
        return out;
    }

    private static ObjectNode positional(PositionalParamSpec p) {
        ObjectNode out = Json.object();
        out.put("name", p.paramLabel().replaceAll("[<>]", ""));
        out.put("type", kind(p));
        out.put("required", p.required());
        out.put("repeatable", p.isMultiValue());
        return out;
    }

    /** The element type: a list's own, or the field's. */
    private static Class<?> element(ArgSpec arg) {
        return arg.isMultiValue() ? arg.auxiliaryTypes()[0] : arg.type();
    }

    private static String kind(ArgSpec arg) {
        Class<?> type = element(arg);
        if (type == boolean.class || type == Boolean.class) {
            return "bool";
        }
        if (type == int.class || type == Integer.class) {
            return "int";
        }
        if (Path.class.isAssignableFrom(type)) {
            return "path";
        }
        return type.isEnum() ? "enum" : "string";
    }

    private static List<String> values(Iterable<String> candidates) {
        List<String> out = new ArrayList<>();
        if (candidates != null) {
            candidates.forEach(out::add);
        }
        return out;
    }
}
