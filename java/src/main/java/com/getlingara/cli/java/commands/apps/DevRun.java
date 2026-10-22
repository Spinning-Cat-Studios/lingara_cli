package com.getlingara.cli.java.commands.apps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.commands.Kind;
import com.getlingara.cli.java.commands.Objects;
import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Interrupts;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.supervise.Group;
import com.getlingara.cli.java.supervise.Shell;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An {@code apps dev} session (ADR 16.10.26ae D7 steps 3, 4 and 8): open it, start the app with
 * the session's credentials, hand over to {@link DevHold}, then end in order — {@code DELETE} the
 * session, stop cloudflared, stop the app's tree — each step running even if the one before it
 * failed. Ctrl+C and SIGTERM are watched for the whole session (ADR 16.10.26af D9), so an
 * interrupted session still ends in that order and <strong>returns</strong> {@code interrupted}.
 * Under {@code --json}, stdout carries one value, written here at exit.
 */
final class DevRun {
    private static final long LISTEN_WAIT_MS = 60_000;
    private static final String APP_PREFIX = "app │ ";

    private DevRun() {}

    static Fail interrupted() {
        return new Fail(Exit.INTERRUPTED, "interrupted", "interrupted");
    }

    static Fail lost(String name) {
        return Fail.message(Exit.DEV_SESSION_LOST, "dev_session_lost", "name", name);
    }

    /** {@code 409 dev_session_held} re-worded with the app's name and the lease. */
    private static Fail held(Fail fail, String name) {
        if (fail.exit() != Exit.DEV_SESSION_HELD) {
            return fail;
        }
        JsonNode body = fail.body() == null ? null : Json.parse(fail.body());
        String lease = Json.text(body, "lease_expires_at", "its lease ends");
        return Fail.message(Exit.DEV_SESSION_HELD, "dev_session_held", "name", name, "lease_expires_at", lease);
    }

    static void session(Ctx ctx, Dev.Plan plan) {
        try (Interrupts.Watch watch = Interrupts.watch()) {
            String clientId = plan.client() ? Json.text(Objects.get(ctx, Kind.APP, plan.id()), "client_id") : null;
            DevApi.Session session;
            try {
                session = DevApi.open(ctx, plan.id(), plan.takeOver(), plan.client());
            } catch (Fail f) {
                throw held(f, plan.name());
            }
            DevState run = new DevState();
            Fail result = null;
            try {
                live(ctx, plan, session, clientId, run);
            } catch (Fail f) {
                result = f;
            }
            end(ctx, plan, session, run);
            if (ctx.out.json()) {
                report(ctx, plan, session, run, result == null ? Exit.OK : result.exit());
            }
            if (result != null) {
                throw result;
            }
        }
    }

    /** Step 8, in order, each step whatever the one before it did. */
    private static void end(Ctx ctx, Dev.Plan plan, DevApi.Session session, DevState run) {
        if (!run.lost) {
            try {
                DevApi.end(ctx, plan.id(), session.id());
            } catch (Fail f) {
                // Parking failed (the network went away, say): the children still stop.
            }
        }
        run.stopChildren();
    }

    private static void report(Ctx ctx, Dev.Plan plan, DevApi.Session session, DevState run, Exit exit) {
        ObjectNode value = Json.object();
        value.put("name", plan.name());
        value.put("app_id", plan.id());
        value.put("session_id", session.id());
        value.set("urls", Json.array(run.urls));
        value.put("exit", exit.word());
        ctx.out.jsonValue(value);
    }

    private static void live(Ctx ctx, Dev.Plan plan, DevApi.Session session, String clientId, DevState run) {
        List<String[]> extra = sessionVars(plan, session, clientId);
        announce(ctx, plan, session, extra);
        run.app = startApp(ctx, plan, Shell.childEnv(ctx.app.env.all(), extra));
        if (!waitApp(plan, run)) {
            return;
        }
        DevHold.firstUrl(ctx, plan, session, run);
        DevHold.hold(ctx, plan, session, run);
    }

    /** What the app's environment gains (D5, D7 step 4): the row's env and env_file, the session's credentials, the port. */
    private static List<String[]> sessionVars(Dev.Plan plan, DevApi.Session session, String clientId) {
        List<String[]> vars = new ArrayList<>(plan.env());
        vars.add(new String[] {"LINGARA_APP_SECRET", session.appSecret()});
        if (clientId != null && session.clientSecret() != null) {
            vars.add(new String[] {"LINGARA_CLIENT_ID", clientId});
            vars.add(new String[] {"LINGARA_CLIENT_SECRET", session.clientSecret()});
        }
        vars.add(new String[] {"PORT", Integer.toString(plan.port())});
        vars.add(new String[] {"LINGARA_APP_PORT", Integer.toString(plan.port())});
        return vars;
    }

    /** {@code --show-env} (names only) and {@code --write-env} (mode 0600), both on stderr. */
    private static void announce(Ctx ctx, Dev.Plan plan, DevApi.Session session, List<String[]> vars) {
        if (plan.showEnv()) {
            vars.forEach(v -> ctx.out.note("env: " + v[0]));
        }
        if (plan.writeEnv() == null) {
            return;
        }
        StringBuilder text = new StringBuilder();
        vars.forEach(v -> text.append(v[0]).append('=').append(v[1]).append('\n'));
        writePrivate(plan.writeEnv(), text.toString());
        ctx.out.note(Assets.message("apps_dev_env_written", "path", plan.writeEnv().toString(), "expires_at", session.expiresAt()));
    }

    private static void writePrivate(Path path, String text) {
        try {
            Files.writeString(path, text, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Not a POSIX filesystem: the file is written, its mode is the platform's.
        } catch (IOException e) {
            throw Fail.failure(path + ": " + e.getMessage());
        }
    }

    private static Group startApp(Ctx ctx, Dev.Plan plan, Map<String, String> env) {
        ProcessBuilder command = Shell.shell(plan.start()).directory(plan.dir().toFile());
        command.environment().clear();
        command.environment().putAll(env);
        command.redirectInput(ProcessBuilder.Redirect.PIPE);
        Group app;
        try {
            app = Group.spawn(command);
            app.process().getOutputStream().close();
        } catch (IOException e) {
            throw Fail.failure(plan.start() + ": " + e.getMessage());
        }
        Shell.relay(app.process().getInputStream(), APP_PREFIX, ctx.out.err());
        Shell.relay(app.process().getErrorStream(), APP_PREFIX, ctx.out.err());
        return app;
    }

    /** The app's own exit: {@code ok} on status 0, else {@code app_exited}. */
    static void appResult(Dev.Plan plan, int status) {
        if (status != 0) {
            throw Fail.message(Exit.APP_EXITED, "app_exited", "name", plan.name(), "status", Integer.toString(status));
        }
    }

    /** Step 4's wait: the port accepts within 60 s. {@code false} when the app already ended the session by exiting 0. */
    private static boolean waitApp(Dev.Plan plan, DevState run) {
        Integer[] exited = {null};
        boolean listening = Shell.waitListening(plan.port(), LISTEN_WAIT_MS, () -> {
            if (exited[0] == null) {
                exited[0] = run.app.exited();
            }
            return Interrupts.raised() || exited[0] != null;
        });
        if (Interrupts.raised()) {
            throw interrupted();
        }
        if (exited[0] != null) {
            appResult(plan, exited[0]);
            return false;
        }
        if (!listening) {
            throw Fail.message(Exit.APP_NOT_LISTENING, "app_not_listening", "name", plan.name(), "port", Integer.toString(plan.port()));
        }
        return true;
    }
}
