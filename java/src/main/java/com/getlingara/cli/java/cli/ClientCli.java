package com.getlingara.cli.java.cli;

import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.cli.Choices.BillingMode;
import com.getlingara.cli.java.cli.Choices.ClientType;
import com.getlingara.cli.java.cli.Mixins.ConfirmFlags;
import com.getlingara.cli.java.cli.Mixins.Deletion;
import com.getlingara.cli.java.cli.Mixins.IdArg;
import com.getlingara.cli.java.commands.Kind;
import com.getlingara.cli.java.commands.OauthClients;
import com.getlingara.cli.java.commands.Objects;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code lingara oauth_client …}, alias {@code client} (16.10.26ad D1). */
@Command(
        name = "oauth_client",
        aliases = "client",
        description = "OAuth clients: the credentials your servers, apps and CI use.",
        subcommands = {
            ClientCli.Create.class,
            ClientCli.ListCmd.class,
            ClientCli.Get.class,
            ClientCli.Update.class,
            ClientCli.Delete.class,
            ClientCli.Secret.class
        })
public final class ClientCli {
    @Command(name = "create", description = "Create an OAuth client; a confidential one's secret is shown once.")
    public static final class Create implements Verb {
        @Option(names = "--name", required = true, description = "The client's name")
        public String name;

        @Option(names = "--scope", paramLabel = "scope", description = "A scope the client may ask for")
        public List<String> scope = new ArrayList<>();

        @Option(names = "--type", converter = ClientType.Conv.class, completionCandidates = ClientType.Of.class,
                description = "Whether the client keeps a secret")
        public ClientType type = ClientType.CONFIDENTIAL;

        @Option(names = "--billing-mode", paramLabel = "mode", converter = BillingMode.Conv.class,
                completionCandidates = BillingMode.Of.class, description = "How its usage is billed")
        public BillingMode billingMode = BillingMode.ALLOWANCE;

        @Option(names = "--redirect-uri", paramLabel = "uri", description = "A redirect URI it may use")
        public List<String> redirectUri = new ArrayList<>();

        @Option(names = "--ci", description = "A CI client: confidential, holding the four platform and canvas scopes")
        public boolean ci;

        @Override
        public void run(Ctx ctx) {
            OauthClients.create(ctx, this);
        }

        @Override
        public List<String[]> conflicts() {
            return List.of(new String[] {"--ci", "--type"}, new String[] {"--ci", "--scope"});
        }
    }

    @Command(name = "list", description = "List your OAuth clients.")
    public static final class ListCmd implements Verb {
        @Override
        public void run(Ctx ctx) {
            OauthClients.list(ctx);
        }
    }

    @Command(name = "get", description = "Show one OAuth client.")
    public static final class Get implements Verb {
        @Mixin
        public IdArg arg;

        @Override
        public void run(Ctx ctx) {
            Objects.printGet(ctx, Kind.OAUTH_CLIENT, arg.id);
        }
    }

    @Command(name = "update", description = "Rename an OAuth client, or replace its scopes, API version or redirect URIs.")
    public static final class Update implements Verb {
        @Parameters(index = "0", paramLabel = "<id>")
        public String id;

        @Option(names = "--name", description = "The new name")
        public String name;

        @Option(names = "--scope", paramLabel = "scope", description = "Replaces the scopes")
        public List<String> scope = new ArrayList<>();

        @Option(names = "--api-version", paramLabel = "version", description = "The API version it is pinned to")
        public String apiVersion;

        @Option(names = "--redirect-uri", paramLabel = "uri", description = "Replaces the redirect URIs")
        public List<String> redirectUri = new ArrayList<>();

        @Override
        public void run(Ctx ctx) {
            OauthClients.update(ctx, this);
        }
    }

    @Command(name = "delete", description = "Delete an OAuth client and everything under it, or one version.")
    public static final class Delete implements Verb {
        @Mixin
        public Deletion args;

        @Override
        public void run(Ctx ctx) {
            OauthClients.delete(ctx, args);
        }
    }

    @Command(
            name = "secret",
            description = "A client's secrets. Rotate by creating the new one, deploying it, then deleting the old one.",
            subcommands = {SecretCreate.class, SecretDelete.class})
    public static final class Secret {}

    @Command(name = "create", description = "Mint a new secret, shown once. The old one stays live.")
    public static final class SecretCreate implements Verb {
        @Parameters(index = "0", paramLabel = "<client_id>")
        public String clientId;

        @Override
        public void run(Ctx ctx) {
            OauthClients.secretCreate(ctx, clientId);
        }
    }

    @Command(name = "delete", description = "Revoke a secret.")
    public static final class SecretDelete implements Verb {
        @Parameters(index = "0", paramLabel = "<client_id>")
        public String clientId;

        @Parameters(index = "1", paramLabel = "<secret_id>")
        public String secretId;

        @Mixin
        public ConfirmFlags confirm;

        @Override
        public void run(Ctx ctx) {
            OauthClients.secretDelete(ctx, this);
        }
    }
}
