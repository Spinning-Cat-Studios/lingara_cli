package com.getlingara.cli.java.commands.apps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.auth.Calls;
import com.getlingara.cli.java.cli.AppsCli;
import com.getlingara.cli.java.cli.Mixins.Deletion;
import com.getlingara.cli.java.commands.Creation;
import com.getlingara.cli.java.commands.Deletes;
import com.getlingara.cli.java.commands.Kind;
import com.getlingara.cli.java.commands.Lists;
import com.getlingara.cli.java.commands.Objects;
import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.http.Request;
import com.getlingara.cli.java.http.Routes;
import com.getlingara.cli.java.http.Routes.Op;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code lingara apps …} (16.10.26ad D1, D2; 16.10.26ae). {@code create} and {@code update} each
 * have <strong>one dispatch point</strong> on whether {@code --manifest} is present: with it,
 * 16.10.26ad's registration path, untouched; without it, 16.10.26ae's scaffolding ({@link
 * Scaffold}) and project-reading ({@link ProjectUpdate}) paths.
 */
public final class Apps {
    private static final String[] COLUMNS = {
        "ID", "id", "NAME", "name", "CLIENT", "parent_id", "SERVING", "serving_version", "VISIBILITY", "visibility"
    };
    private static final String[] VERSION_COLUMNS = {
        "SEMVER", "semver", "STATE", "state", "STATE_AT", "state_at", "REVIEWED", "review_basis"
    };

    private Apps() {}

    public static void list(Ctx ctx) {
        Lists.printList(ctx, Kind.APP, COLUMNS);
    }

    /** The one rule {@code conflicts} cannot say: {@code --manifest} and {@code --client} come together, or not at all. */
    public static void createEither(Ctx ctx, AppsCli.Create args) {
        if (args.manifest != null && args.client != null) {
            create(ctx, args);
        } else if (args.manifest == null && args.client == null) {
            Scaffold.run(ctx, args);
        } else {
            throw Fail.usage("--manifest and --client go together: both register an app from a file, neither scaffolds a project");
        }
    }

    /** {@code --manifest <path>} is the file's JSON as the app body's {@code manifest}. */
    static ObjectNode manifestBody(Path path) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw Fail.usage(path + ": " + e.getMessage());
        }
        JsonNode manifest = Json.parse(text);
        if (manifest == null) {
            throw Fail.usage(path + " is not JSON: " + Json.parseError(text));
        }
        ObjectNode body = Json.object();
        body.set("manifest", manifest);
        return body;
    }

    /** The registration form: register the object, write no files. */
    private static void create(Ctx ctx, AppsCli.Create args) {
        ObjectNode body = manifestBody(args.manifest);
        Creation made = new Creation();
        made.conclude(ctx, () -> new Creation.Last(Kind.APP, Json.text(made.object(ctx, Kind.APP, args.name, args.client, body), "id", "")));
    }

    public static void updateEither(Ctx ctx, AppsCli.Update args) {
        if (args.manifest == null) {
            ProjectUpdate.run(ctx, args);
            return;
        }
        if (args.id == null) {
            throw Fail.usage("apps update --manifest needs the app's id");
        }
        JsonNode manifest = manifestBody(args.manifest).get("manifest");
        // A quick-tunnel or dev.invalid render URL is refused before any request (16.10.26ae D6 step 3).
        if (ManifestRules.ephemeral(Json.text(manifest, "render_url", ""))) {
            throw Fail.message(Exit.RENDER_URL_EPHEMERAL, "render_url_ephemeral", "file", args.manifest.toString());
        }
        ctx.out.value(Objects.bodyUpdate(ctx, Kind.APP, args.id, body -> body.set("manifest", manifest.deepCopy())));
    }

    public static void delete(Ctx ctx, Deletion args) {
        Deletes.deleteObject(ctx, Kind.APP, args, o -> Assets.message("consequence_app", "client", Deletes.parent(o)));
    }

    /** {@code {"visibility": "public"}}: review itself is the operator's, in the portal. */
    public static void submit(Ctx ctx, String id, String version) {
        ObjectNode body = Json.object();
        body.put("visibility", "public");
        Request request = Request.of(Routes.object(Kind.APP.word(), Op.SUBMIT), Map.of("id", id, "semver", version)).withJson(body);
        ctx.out.value(Calls.call(ctx, request));
    }

    public static void publish(Ctx ctx, String id, String semver) {
        ctx.out.value(Objects.versionOp(ctx, Kind.APP, Op.PUBLISH, id, semver));
    }

    public static void withdraw(Ctx ctx, String id, String semver) {
        ctx.out.value(Objects.versionOp(ctx, Kind.APP, Op.WITHDRAW, id, semver));
    }

    /** Every page, newest first; {@code next_cursor} is the last semver seen. */
    public static void listVersions(Ctx ctx, String id) {
        List<JsonNode> items = Lists.pages(ctx, Request.of(Routes.object(Kind.APP.word(), Op.VERSIONS), Map.of("id", id)), List.of());
        Lists.printRows(ctx, items, VERSION_COLUMNS);
    }
}
