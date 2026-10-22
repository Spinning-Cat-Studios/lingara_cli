package com.getlingara.cli.java.cli;

import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.cli.Mixins.ConfirmFlags;
import com.getlingara.cli.java.cli.Mixins.Deletion;
import com.getlingara.cli.java.cli.Mixins.IdArg;
import com.getlingara.cli.java.commands.Inbound;
import com.getlingara.cli.java.commands.Kind;
import com.getlingara.cli.java.commands.Objects;
import com.getlingara.cli.java.commands.Outbound;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code lingara webhook inbound|outbound …} (16.10.26ad D1), plus {@code replace} and {@code
 * test} on outbound. Outbound's verbs are {@link OutboundCli}'s.
 */
@Command(
        name = "webhook",
        description = "Webhooks: events your game sends in, and events Lingara sends out.",
        subcommands = {WebhookCli.InboundCli.class, OutboundCli.class})
public final class WebhookCli {
    @Command(
            name = "inbound",
            description = "Inbound webhooks: a confidential client a sender uses for POST /v1/events.",
            subcommands = {InCreate.class, InList.class, InGet.class, InUpdate.class, InDelete.class})
    public static final class InboundCli {}

    @Command(name = "create", description = "Create an inbound webhook and its client, and show the client's secret once.")
    public static final class InCreate implements Verb {
        @Option(names = "--name", required = true, description = "The webhook's name")
        public String name;

        @Option(names = "--event", paramLabel = "type", description = "An inbound event type it accepts; absent, every one")
        public List<String> event = new ArrayList<>();

        @Override
        public void run(Ctx ctx) {
            Inbound.create(ctx, name, event);
        }
    }

    @Command(name = "list", description = "List your inbound webhooks.")
    public static final class InList implements Verb {
        @Override
        public void run(Ctx ctx) {
            Inbound.list(ctx);
        }
    }

    @Command(name = "get", description = "Show one inbound webhook.")
    public static final class InGet implements Verb {
        @Mixin
        public IdArg arg;

        @Override
        public void run(Ctx ctx) {
            Objects.printGet(ctx, Kind.WEBHOOK_INBOUND, arg.id);
        }
    }

    @Command(name = "update", description = "Rename an inbound webhook, or replace its event types.")
    public static final class InUpdate implements Verb {
        @Parameters(index = "0", paramLabel = "<id>")
        public String id;

        @Option(names = "--name", description = "The new name")
        public String name;

        @Option(names = "--event", paramLabel = "type", description = "Replaces the event types")
        public List<String> event = new ArrayList<>();

        @Override
        public void run(Ctx ctx) {
            Inbound.update(ctx, id, name, event);
        }
    }

    @Command(name = "delete", description = "Delete an inbound webhook, or one version. Its client stays.")
    public static final class InDelete implements Verb {
        @Mixin
        public Deletion args;

        @Override
        public void run(Ctx ctx) {
            Inbound.delete(ctx, args);
        }
    }

    /** {@code webhook outbound replace}'s flags, which {@code Replace} reads. */
    @Command(name = "replace", description = "Start again: a new endpoint with a new signing secret, then the old one deleted.")
    public static final class OutReplace implements Verb {
        @Parameters(index = "0", paramLabel = "<id>")
        public String id;

        @Option(names = "--url", paramLabel = "url", description = "The new endpoint; absent, the old one's")
        public String url;

        @Option(names = "--event", paramLabel = "type", description = "The new event types; absent, the old one's")
        public List<String> event = new ArrayList<>();

        @Mixin
        public ConfirmFlags confirm;

        @Override
        public void run(Ctx ctx) {
            Outbound.replace(ctx, this);
        }
    }
}
