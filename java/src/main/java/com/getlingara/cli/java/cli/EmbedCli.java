package com.getlingara.cli.java.cli;

import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.cli.Mixins.Deletion;
import com.getlingara.cli.java.cli.Mixins.IdArg;
import com.getlingara.cli.java.commands.Embeds;
import com.getlingara.cli.java.commands.Kind;
import com.getlingara.cli.java.commands.Objects;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code lingara embed …} (16.10.26ad D1, D2): an embed and the metered client behind it. */
@Command(
        name = "embed",
        description = "Lingara inside your game or website: an embed and the metered OAuth client behind it.",
        subcommands = {EmbedCli.Create.class, EmbedCli.ListCmd.class, EmbedCli.Get.class, EmbedCli.Update.class, EmbedCli.Delete.class})
public final class EmbedCli {
    @Command(name = "create", description = "Create an embed and its metered OAuth client, and show the client's secret once.")
    public static final class Create implements Verb {
        @Option(names = "--name", required = true, description = "The embed's name")
        public String name;

        @Option(names = "--origin", paramLabel = "origin", description = "A web origin allowed to use it (up to 10)")
        public List<String> origin = new ArrayList<>();

        @Option(names = "--scope", paramLabel = "scope", description = "A scope beside embed:mint")
        public List<String> scope = new ArrayList<>();

        @Override
        public void run(Ctx ctx) {
            Embeds.create(ctx, this);
        }
    }

    @Command(name = "list", description = "List your embeds.")
    public static final class ListCmd implements Verb {
        @Override
        public void run(Ctx ctx) {
            Embeds.list(ctx);
        }
    }

    @Command(name = "get", description = "Show one embed.")
    public static final class Get implements Verb {
        @Mixin
        public IdArg arg;

        @Override
        public void run(Ctx ctx) {
            Objects.printGet(ctx, Kind.EMBED, arg.id);
        }
    }

    @Command(name = "update", description = "Rename an embed, or replace its origins.")
    public static final class Update implements Verb {
        @Parameters(index = "0", paramLabel = "<id>")
        public String id;

        @Option(names = "--name", description = "The new name")
        public String name;

        @Option(names = "--origin", paramLabel = "origin", description = "Replaces the allowed origins")
        public List<String> origin = new ArrayList<>();

        @Override
        public void run(Ctx ctx) {
            Embeds.update(ctx, this);
        }
    }

    @Command(name = "delete", description = "Delete an embed and its OAuth client, or one version.")
    public static final class Delete implements Verb {
        @Mixin
        public Deletion args;

        @Override
        public void run(Ctx ctx) {
            Embeds.delete(ctx, args);
        }
    }
}
