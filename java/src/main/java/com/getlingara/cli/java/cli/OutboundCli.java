package com.getlingara.cli.java.cli;

import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.cli.Mixins.Deletion;
import com.getlingara.cli.java.cli.Mixins.IdArg;
import com.getlingara.cli.java.commands.Kind;
import com.getlingara.cli.java.commands.Objects;
import com.getlingara.cli.java.commands.Outbound;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code lingara webhook outbound …} (16.10.26ad D1, D2). */
@Command(
        name = "outbound",
        description = "Outbound webhooks: events Lingara delivers to your endpoint, signed.",
        subcommands = {
            OutboundCli.Create.class,
            OutboundCli.ListCmd.class,
            OutboundCli.Get.class,
            OutboundCli.Update.class,
            WebhookCli.OutReplace.class,
            OutboundCli.Test.class,
            OutboundCli.Delete.class
        })
public final class OutboundCli {
    @Command(name = "create", description = "Create an outbound webhook, and show its signing secret once.")
    public static final class Create implements Verb {
        @Option(names = "--client", paramLabel = "id", required = true, description = "The OAuth client it belongs to")
        public String client;

        @Option(names = "--url", paramLabel = "url", required = true, description = "The endpoint deliveries go to")
        public String url;

        @Option(names = "--event", paramLabel = "type", required = true, description = "An event type to deliver")
        public List<String> event = new ArrayList<>();

        @Override
        public void run(Ctx ctx) {
            Outbound.create(ctx, client, url, event);
        }
    }

    @Command(name = "list", description = "List your outbound webhooks.")
    public static final class ListCmd implements Verb {
        @Override
        public void run(Ctx ctx) {
            Outbound.list(ctx);
        }
    }

    @Command(name = "get", description = "Show one outbound webhook.")
    public static final class Get implements Verb {
        @Mixin
        public IdArg arg;

        @Override
        public void run(Ctx ctx) {
            Objects.printGet(ctx, Kind.WEBHOOK_OUTBOUND, arg.id);
        }
    }

    @Command(name = "update",
            description = "Change an outbound webhook's URL or event types, keeping its signing secret, or enable or disable it.")
    public static final class Update implements Verb {
        @Parameters(index = "0", paramLabel = "<id>")
        public String id;

        @Option(names = "--url", paramLabel = "url", description = "The new endpoint")
        public String url;

        @Option(names = "--event", paramLabel = "type", description = "Replaces the event types")
        public List<String> event = new ArrayList<>();

        @Option(names = "--enable", description = "Resume deliveries")
        public boolean enable;

        @Option(names = "--disable", description = "Pause deliveries")
        public boolean disable;

        @Override
        public void run(Ctx ctx) {
            ctx.out.value(Outbound.update(ctx, this));
        }

        @Override
        public List<String[]> conflicts() {
            return List.<String[]>of(new String[] {"--enable", "--disable"});
        }
    }

    @Command(name = "test", description = "Send a test delivery.")
    public static final class Test implements Verb {
        @Mixin
        public IdArg arg;

        @Override
        public void run(Ctx ctx) {
            Outbound.test(ctx, arg.id);
        }
    }

    @Command(name = "delete", description = "Delete an outbound webhook, or one version.")
    public static final class Delete implements Verb {
        @Mixin
        public Deletion args;

        @Override
        public void run(Ctx ctx) {
            Outbound.delete(ctx, args);
        }
    }
}
