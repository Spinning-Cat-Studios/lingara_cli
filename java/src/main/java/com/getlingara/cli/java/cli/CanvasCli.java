package com.getlingara.cli.java.cli;

import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.canvas.Apply;
import com.getlingara.cli.java.cli.Mixins.Deletion;
import com.getlingara.cli.java.cli.Mixins.IdArg;
import com.getlingara.cli.java.commands.Canvases;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code lingara canvas …} (16.10.26ad D1, D7): content through {@code /v1/canvases}, lifecycle through {@code /platform/objects}. */
@Command(
        name = "canvas",
        description = "Canvases: builder flows that create their objects only when applied.",
        subcommands = {
            CanvasCli.Create.class,
            CanvasCli.ListCmd.class,
            CanvasCli.Get.class,
            CanvasCli.Update.class,
            CanvasCli.Draft.class,
            CanvasCli.ApplyCmd.class,
            CanvasCli.Delete.class
        })
public final class CanvasCli {
    @Command(name = "create", description = "Create a canvas, from a definition file or empty.")
    public static final class Create implements Verb {
        @Option(names = "--name", description = "The canvas's name")
        public String name;

        @Option(names = "--file", paramLabel = "definition.json", description = "The definition; absent, an empty one")
        public Path file;

        @Override
        public void run(Ctx ctx) {
            Canvases.create(ctx, name, file);
        }
    }

    @Command(name = "list", description = "List your canvases.")
    public static final class ListCmd implements Verb {
        @Override
        public void run(Ctx ctx) {
            Canvases.list(ctx);
        }
    }

    @Command(name = "get", description = "Show one canvas.")
    public static final class Get implements Verb {
        @Mixin
        public IdArg arg;

        @Override
        public void run(Ctx ctx) {
            ctx.out.value(Canvases.get(ctx, arg.id));
        }
    }

    @Command(name = "update", description = "Rename a canvas.")
    public static final class Update implements Verb {
        @Parameters(index = "0", paramLabel = "<id>")
        public String id;

        @Option(names = "--name", required = true, description = "The new name")
        public String name;

        @Override
        public void run(Ctx ctx) {
            Canvases.rename(ctx, id, name);
        }
    }

    @Command(name = "draft", description = "Save a definition as the canvas's draft. Creates nothing else.")
    public static final class Draft implements Verb {
        @Parameters(index = "0", paramLabel = "<id>")
        public String id;

        @Option(names = "--file", paramLabel = "definition.json", required = true, description = "The definition")
        public Path file;

        @Override
        public void run(Ctx ctx) {
            Canvases.draft(ctx, id, file);
        }
    }

    @Command(name = "apply", description = "Create the canvas's objects by running this CLI's own commands, then activate it.")
    public static final class ApplyCmd implements Verb {
        @Parameters(index = "0", paramLabel = "<id>")
        public String id;

        @Option(names = "--dry-run", description = "Print what each object would do, and write nothing")
        public boolean dryRun;

        @Option(names = "--print-commands", description = "Print the commands apply would run, and write nothing")
        public boolean printCommands;

        @Override
        public void run(Ctx ctx) {
            Apply.run(ctx, id, dryRun, printCommands);
        }

        @Override
        public List<String[]> conflicts() {
            return List.<String[]>of(new String[] {"--dry-run", "--print-commands"});
        }
    }

    @Command(name = "delete", description = "Delete a canvas, or one version. The objects it made stay.")
    public static final class Delete implements Verb {
        @Mixin
        public Deletion args;

        @Override
        public void run(Ctx ctx) {
            Canvases.delete(ctx, args);
        }
    }
}
