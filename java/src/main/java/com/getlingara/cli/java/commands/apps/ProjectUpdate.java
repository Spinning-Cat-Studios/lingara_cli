package com.getlingara.cli.java.commands.apps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.auth.Calls;
import com.getlingara.cli.java.cli.AppsCli;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.http.Request;
import com.getlingara.cli.java.http.Routes;
import com.getlingara.cli.java.http.Routes.Op;
import com.getlingara.cli.java.platform.AppRow;
import java.util.List;
import java.util.Map;

/**
 * {@code lingara apps update [name]} without {@code --manifest} (ADR 16.10.26ae D6): the project's
 * manifest goes to the <strong>dev version</strong> ({@code PATCH …/dev}, never its {@code
 * render_url}), or with {@code --version} becomes an immutable draft. A row with no id is
 * registered first.
 */
final class ProjectUpdate {
    private ProjectUpdate() {}

    static void run(Ctx ctx, AppsCli.Update args) {
        Rows.Opened opened = Rows.open(ctx, args.config.config);
        AppRow row = Rows.resolve(opened.toml(), args.id, opened.cwd());
        JsonNode manifest = Project.read(opened.toml(), row, opened.cwd());
        if (args.version != null && ManifestRules.ephemeral(Json.text(manifest, "render_url", ""))) {
            String file = Project.source(opened.toml(), row, opened.cwd());
            throw Fail.message(Exit.RENDER_URL_EPHEMERAL, "render_url_ephemeral", "file", file);
        }
        List<String> warnings = Project.warn(ctx, manifest);
        if (row.id() == null) {
            Project.register(ctx, opened.toml(), row, manifest);
            return;
        }
        JsonNode answer = args.version != null
                ? cut(ctx, row.id(), args.version, manifest)
                : DevApi.patchWithRetry(ctx, row.id(), draft -> {
                    ObjectNode body = Json.object();
                    body.set("manifest", manifest);
                    body.set("draft_version", draft);
                    return body;
                });
        if (ctx.out.json() && answer.isObject()) {
            ((ObjectNode) answer).set("warnings", Json.array(warnings));
        }
        ctx.out.value(answer);
    }

    /**
     * Step 3: an immutable draft from the project's manifest. The server's lifecycle rules stand (a
     * semver not greater than the last, a second editable version), as {@code refused}.
     */
    private static JsonNode cut(Ctx ctx, String id, String semver, JsonNode manifest) {
        ObjectNode body = Json.object();
        body.put("semver", semver);
        body.putObject("body").set("manifest", manifest);
        return Calls.call(ctx, Request.of(Routes.object("app", Op.VERSION_CREATE), Map.of("id", id)).withJson(body));
    }
}
