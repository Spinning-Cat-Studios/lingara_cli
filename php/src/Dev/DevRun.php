<?php

declare(strict_types=1);

namespace Lingara\Cli\Dev;

use Lingara\Cli\Core\Assets;
use Lingara\Cli\Core\Ctx;
use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;
use Lingara\Cli\Core\Json;
use Lingara\Cli\Platform\Kind;
use Lingara\Cli\Platform\Objects;
use Lingara\Cli\Project\ProjectManifest;

/**
 * An `apps dev` session (ADR 16.10.26ae D7 steps 3, 4 and 8): open it,
 * start the app with the session's credentials, hand over to `DevHold`, then
 * end in order — `DELETE` the session, stop cloudflared, stop the app's
 * group — each step running even if the one before it failed. Ctrl+C and
 * SIGTERM are a flag the loops poll from the open on (ADR 16.10.26af D9), so
 * an interrupted session still parks and still returns 130. Under `--json`,
 * stdout carries one value, written here at exit.
 */
final class DevRun
{
    private const LISTEN_WAIT_SECS = 60;
    private const STOP_GRACE_SECS = 5;

    /** The CLI's own credentials, never the app's (D5): `LINGARA_PLATFORM_*`. */
    public const SCRUBBED_PREFIX = 'LINGARA_PLATFORM_';

    /** `409 dev_session_held` re-worded with the app's name and the lease. */
    private static function held(Fail $fail, string $name): Fail
    {
        if ($fail->exit !== ExitCode::DevSessionHeld) {
            return $fail;
        }
        $lease = Json::opt(Json::decode($fail->body ?? ''), 'lease_expires_at') ?? 'its lease ends';
        return Fail::message(ExitCode::DevSessionHeld, 'dev_session_held', ['name' => $name, 'lease_expires_at' => $lease]);
    }

    /**
     * The parent's environment, minus the CLI's credentials, plus `$extra` (later entries win).
     *
     * @param array<string, string> $parent
     * @param list<array{string, string}> $extra
     * @return array<string, string|false> false removes a variable symfony/process would otherwise inherit
     */
    public static function childEnv(array $parent, array $extra): array
    {
        $env = [];
        foreach ($parent as $key => $value) {
            $env[$key] = str_starts_with($key, self::SCRUBBED_PREFIX) ? false : $value;
        }
        foreach ($extra as [$key, $value]) {
            $env[$key] = $value;
        }
        return $env;
    }

    /**
     * What the app's environment gains (D5, D7 step 4): the row's `env` and
     * `env_file`, the session's credentials, and the port.
     *
     * @return list<array{string, string}>
     */
    private static function sessionVars(RunState $run, ?string $clientId): array
    {
        $port = (string) $run->plan['port'];
        /** @var list<array{string, string}> $vars */
        $vars = [...$run->plan['env'], ['LINGARA_APP_SECRET', $run->session['app_secret']]];
        if ($clientId !== null && $run->session['client_secret'] !== null) {
            array_push($vars, ['LINGARA_CLIENT_ID', $clientId], ['LINGARA_CLIENT_SECRET', $run->session['client_secret']]);
        }
        array_push($vars, ['PORT', $port], ['LINGARA_APP_PORT', $port]);
        return $vars;
    }

    /** @param list<array{string, string}> $vars `--show-env` (names only) and `--write-env` (mode 0600), both on stderr */
    private static function announce(RunState $run, array $vars): void
    {
        if ($run->plan['show_env'] === true) {
            foreach ($vars as [$name]) {
                $run->ctx->out->note("env: {$name}");
            }
        }
        $path = $run->plan['write_env'] ?? null;
        if (!is_string($path)) {
            return;
        }
        touch($path);
        chmod($path, 0600);
        file_put_contents($path, implode('', array_map(static fn (array $v): string => "{$v[0]}={$v[1]}\n", $vars)));
        $run->ctx->out->note(Assets::message('apps_dev_env_written', ['path' => $path, 'expires_at' => $run->session['expires_at']]));
    }

    /** The app's own exit: `ok` on status 0, else `app_exited`. */
    public static function appResult(RunState $run): void
    {
        $run->pump();
        $status = $run->app?->status();
        if ($status === 0) {
            return;
        }
        throw Fail::message(ExitCode::AppExited, 'app_exited', ['name' => (string) $run->plan['name'], 'status' => $status === null ? 'a signal' : (string) $status]);
    }

    /** Step 4's wait: the port accepts within 60 s. False when the app already ended the session by exiting 0. */
    private static function waitApp(RunState $run, Group $app): bool
    {
        $deadline = microtime(true) + self::LISTEN_WAIT_SECS;
        while (!Probe::accepts((int) $run->plan['port'])) {
            if ($run->stopped()) {
                throw DevHold::interrupted();
            }
            if ($app->done()) {
                self::appResult($run);
                return false;
            }
            if (microtime(true) >= $deadline) {
                throw Fail::message(ExitCode::AppNotListening, 'app_not_listening', ['name' => (string) $run->plan['name'], 'port' => (string) $run->plan['port']]);
            }
            usleep(100_000);
        }
        return true;
    }

    private static function live(RunState $run, ?string $clientId): void
    {
        $vars = self::sessionVars($run, $clientId);
        self::announce($run, $vars);
        $env = self::childEnv($run->ctx->app->env->all(), $vars);
        $run->app = Group::spawn(ProjectManifest::shell((string) $run->plan['start']), (string) $run->plan['dir'], $env);
        if (self::waitApp($run, $run->app)) {
            DevHold::firstUrl($run);
            DevHold::hold($run);
        }
    }

    /** @param array<string, mixed> $plan the public client id the app's pair names, read before the open mints its secret */
    private static function clientId(Ctx $ctx, array $plan): ?string
    {
        if ($plan['client'] !== true) {
            return null;
        }
        $id = Json::opt(Objects::get($ctx, Kind::app(), (string) $plan['id']), 'client_id');
        return $id === '' ? null : $id;
    }

    /** @param array<string, mixed> $plan the session, from the open to the last child stopped */
    public static function session(Ctx $ctx, array $plan): void
    {
        Signals::trap();
        try {
            $plan['client_id'] = self::clientId($ctx, $plan);
            try {
                $opened = DevApi::open($ctx, (string) $plan['id'], $plan['take_over'] === true, $plan['client'] === true);
            } catch (Fail $fail) {
                throw self::held($fail, (string) $plan['name']);
            }
            self::run(new RunState($ctx, $plan, $opened));
        } finally {
            Signals::release();
        }
    }

    private static function run(RunState $run): void
    {
        $error = null;
        try {
            self::live($run, is_string($run->plan['client_id'] ?? null) ? $run->plan['client_id'] : null);
        } catch (\Throwable $e) {
            $error = Fail::of($e);
        }
        self::stop($run);
        if ($run->ctx->out->json) {
            $exit = $error === null ? ExitCode::Ok : $error->exit;
            $run->ctx->out->jsonValue(['name' => $run->plan['name'], 'app_id' => $run->plan['id'], 'session_id' => $run->session['id'], 'urls' => $run->urls, 'exit' => $exit->label()]);
        }
        if ($error !== null) {
            throw $error;
        }
    }

    /** Step 8, in order, each part running even if the one before it failed: park the session, stop cloudflared, stop the app's group. */
    private static function stop(RunState $run): void
    {
        if (!$run->lost) {
            try {
                DevApi::end($run->ctx, (string) $run->plan['id'], $run->session['id']);
            } catch (Fail) {
                // The session expires on its own; the children still stop.
            }
        }
        $run->tunnel?->stop();
        $run->app?->stop(self::STOP_GRACE_SECS);
        $run->pump();
    }
}
