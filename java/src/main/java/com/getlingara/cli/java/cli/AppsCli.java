package com.getlingara.cli.java.cli;

import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.cli.Choices.Lang;
import com.getlingara.cli.java.cli.Choices.Slot;
import com.getlingara.cli.java.cli.Mixins.ConfigOption;
import com.getlingara.cli.java.cli.Mixins.Deletion;
import com.getlingara.cli.java.cli.Mixins.IdArg;
import com.getlingara.cli.java.commands.Kind;
import com.getlingara.cli.java.commands.Objects;
import com.getlingara.cli.java.commands.apps.Apps;
import com.getlingara.cli.java.commands.apps.Local;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code lingara apps …} (16.10.26ad D1; 16.10.26ae D1, D6, D7). {@code create} and {@code
 * update} each carry both forms, the registration one ({@code --manifest}) and the project one;
 * their one dispatch point is {@link Apps}. {@code dev} and the version verbs are {@link
 * AppsMoreCli}'s.
 */
@Command(
        name = "apps",
        description = "Apps that render cards inside Lingara.",
        subcommands = {
            AppsCli.Create.class,
            AppsCli.ListCmd.class,
            AppsCli.Get.class,
            AppsCli.Update.class,
            AppsMoreCli.Dev.class,
            AppsMoreCli.Status.class,
            AppsMoreCli.Pin.class,
            AppsMoreCli.Unpin.class,
            AppsCli.Delete.class,
            AppsMoreCli.Submit.class,
            AppsMoreCli.Versions.class
        })
public final class AppsCli {
    private static final String[] REGISTRATION_ONLY = {"--lang", "--bare", "--dir"};
    private static final String[] SCAFFOLD_ONLY = {
        "--lang", "--bare", "--dir", "--no-register", "--description", "--port", "--slot", "--context"
    };

    @Command(name = "create",
            description = "Scaffold an app project and register it; or, with --client and --manifest, register an app from a manifest file.")
    public static final class Create implements Verb {
        @Parameters(index = "0", paramLabel = "<name>")
        public String name;

        @Option(names = "--client", paramLabel = "id", description = "The OAuth client's id (with --manifest)")
        public String client;

        @Option(names = "--manifest", paramLabel = "path", description = "The app's manifest file (with --client)")
        public Path manifest;

        @Option(names = "--lang", paramLabel = "app-kit-lang", converter = Lang.Conv.class, completionCandidates = Lang.Of.class,
                description = "The app's language")
        public Lang lang;

        @Option(names = "--bare", description = "The manifest as code in the app's language, and a handler on the kit's primitives")
        public boolean bare;

        @Option(names = "--dir", paramLabel = "path", description = "Where to write the project; default ./<name>")
        public Path dir;

        @Option(names = "--no-register", description = "Scaffold only; register later with apps update")
        public boolean noRegister;

        @Option(names = "--description", paramLabel = "text", description = "One sentence about the app")
        public String description;

        @Option(names = "--port", paramLabel = "n", converter = Choices.Port.class, description = "The port the app listens on; default 8787")
        public Integer port;

        @Option(names = "--slot", paramLabel = "slot", converter = Slot.Conv.class, completionCandidates = Slot.Of.class,
                description = "Where the app's card may appear; default home.side")
        public List<Slot> slot = new ArrayList<>();

        @Option(names = "--context", paramLabel = "slice", description = "A context slice the app receives")
        public List<String> context = new ArrayList<>();

        @Mixin
        public ConfigOption config;

        @Override
        public void run(Ctx ctx) {
            Apps.createEither(ctx, this);
        }

        @Override
        public List<String[]> conflicts() {
            List<String[]> out = new ArrayList<>();
            for (String other : REGISTRATION_ONLY) {
                out.add(new String[] {"--client", other});
            }
            for (String other : SCAFFOLD_ONLY) {
                out.add(new String[] {"--manifest", other});
            }
            return out;
        }
    }

    @Command(name = "list", description = "List your apps, or the apps platform.toml pins.")
    public static final class ListCmd implements Verb {
        @Option(names = "--pinned", description = "List platform.toml's rows instead, reading nothing from the platform")
        public boolean pinned;

        @Mixin
        public ConfigOption config;

        @Override
        public void run(Ctx ctx) {
            if (pinned) {
                Local.listPinned(ctx, config.config);
            } else {
                Apps.list(ctx);
            }
        }
    }

    @Command(name = "get", description = "Show one app.")
    public static final class Get implements Verb {
        @Mixin
        public IdArg arg;

        @Override
        public void run(Ctx ctx) {
            Objects.printGet(ctx, Kind.APP, arg.id);
        }
    }

    @Command(name = "update",
            description = "Without --manifest, push the project's manifest to its dev version (--version cuts a draft); with an id and --manifest, make a new version from a file.")
    public static final class Update implements Verb {
        @Parameters(index = "0", arity = "0..1", paramLabel = "<id>")
        public String id;

        @Option(names = "--manifest", paramLabel = "path", description = "The app's manifest file: makes a new version")
        public Path manifest;

        @Option(names = "--version", paramLabel = "semver", description = "Cut a draft version from the project's manifest")
        public String version;

        @Mixin
        public ConfigOption config;

        @Override
        public void run(Ctx ctx) {
            Apps.updateEither(ctx, this);
        }

        @Override
        public List<String[]> conflicts() {
            return List.<String[]>of(new String[] {"--version", "--manifest"});
        }
    }

    @Command(name = "delete", description = "Delete an app, or one of its versions.")
    public static final class Delete implements Verb {
        @Mixin
        public Deletion args;

        @Override
        public void run(Ctx ctx) {
            Apps.delete(ctx, args);
        }
    }
}
