<?php

declare(strict_types=1);

namespace Lingara\Cli\Canvas;

use Lingara\Cli\Core\Ctx;
use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;
use Lingara\Cli\Core\Json;
use Lingara\Cli\Http\Request;
use Lingara\Cli\Http\Routes;
use Lingara\Cli\Platform\Kind;
use Lingara\Cli\Platform\Objects;

/**
 * `canvas apply <id>`, D7 steps 1–3, and the two flags that stop there:
 * `--dry-run` prints the verdicts, `--print-commands` the argvs. Neither
 * writes. Steps 4–7 are `Execute`'s.
 */
final class Apply
{
    private static function refused(string $message): Fail
    {
        return new Fail(ExitCode::Refused, 'canvas_apply_refused', $message);
    }

    public static function getCanvas(Ctx $ctx, string $id): mixed
    {
        return $ctx->call(new Request(Routes::fixed('canvas.get'), ['id' => $id]));
    }

    /**
     * The active record overlaid by the draft's, kept to the draft's nodes.
     *
     * @return array<string, string>
     */
    private static function recorded(mixed $canvas, Definition $def): array
    {
        $all = [];
        foreach (['active', 'draft'] as $side) {
            foreach (get_object_vars(Json::object(Json::get(Json::get($canvas, $side), 'objects'))) as $handle => $v) {
                if (is_string(Json::get($v, 'id'))) {
                    $all[(string) $handle] = Json::str($v, 'id');
                }
            }
        }
        $handles = array_column($def->nodes, 'id');
        return array_filter($all, static fn (string $h): bool => in_array($h, $handles, true), ARRAY_FILTER_USE_KEY);
    }

    private static function refusalFail(Refusal $r): Fail
    {
        return match ($r->why) {
            'ephemeral' => Fail::message(ExitCode::RenderUrlEphemeral, 'render_url_ephemeral', ['file' => "node {$r->handle}'s manifest"]),
            'script' => self::refused("node {$r->handle} runs a script; apply this canvas in the browser"),
            'fields' => self::refused("node {$r->handle}'s fields are not one the builder can apply: {$r->getMessage()}"),
            'no_client' => self::refused("node {$r->handle} needs exactly one client node upstream"),
            default => self::refused("the canvas's edges form a cycle"),
        };
    }

    /**
     * D7 steps 1–2: the canvas and the three lists, every page, then the plan.
     *
     * @return array{id: string, draft_version: mixed, kept: array<string, string>, live: array<string, list<array{id: string, runtime_id: string, parent_id: string}>>, ops: list<array<string, mixed>>}
     */
    public static function plan(Ctx $ctx, string $id): array
    {
        $canvas = self::getCanvas($ctx, $id);
        $draft = Json::get($canvas, 'draft') ?? throw self::refused('nothing to apply: save a draft with `lingara canvas draft`');
        $rows = static fn (Kind $k): array => array_map([Plan::class, 'row'], Objects::list($ctx, $k));
        $live = ['client' => $rows(Kind::oauthClient()), 'webhook' => $rows(Kind::webhookOutbound()), 'app' => $rows(Kind::app())];
        $def = Definition::read(Json::get($draft, 'definition'));
        $kept = Plan::stillLive($def, self::recorded($canvas, $def), $live);
        try {
            $ops = Plan::build($def, $kept);
        } catch (Refusal $r) {
            throw self::refusalFail($r);
        }
        $version = Json::get($draft, 'draft_version');
        return ['id' => $id, 'draft_version' => is_int($version) ? $version : 0, 'kept' => $kept, 'live' => $live, 'ops' => $ops];
    }

    private static function stopFail(Stop $stop): Fail
    {
        if ($stop->replace !== null) {
            return self::refused("node {$stop->replace}: unexpected `would_replace` from the server; nothing was applied");
        }
        $fail = self::refused('the dry-run refused ' . count($stop->refused) . ' op(s); nothing was applied');
        foreach ($stop->refused as [$handle, $code, $error]) {
            $fail->hint("{$handle}: {$error} ({$code})");
        }
        return $fail;
    }

    /**
     * Each app op's manifest, as the file its `--manifest` names.
     *
     * @param list<array<string, mixed>> $ops
     * @return array<string, string>
     */
    private static function writeManifests(array $ops): array
    {
        $out = [];
        foreach ($ops as $op) {
            if ($op['kind'] === 'app') {
                $path = sys_get_temp_dir() . '/lingara-' . getmypid() . "-{$op['handle']}.manifest.json";
                $body = is_array($op['body'] ?? null) ? $op['body'] : [];
                file_put_contents($path, Json::encode($body['manifest'] ?? null));
                $out[(string) $op['handle']] = $path;
            }
        }
        return $out;
    }

    /**
     * The matryoshka made visible: what apply would run, as shell lines.
     *
     * @param list<array<string, mixed>> $steps
     * @param array<string, string> $manifests
     */
    private static function printCommands(Ctx $ctx, array $steps, array $manifests): void
    {
        $lines = [];
        foreach ($steps as $step) {
            if ($step['argv'] !== []) {
                /** @var list<array<string, string>> $argv */
                $argv = $step['argv'];
                $lines[] = Steps::shellLine($argv, static fn (string $h): string => $manifests[$h] ?? '');
            }
        }
        if ($ctx->out->json) {
            $ctx->out->jsonValue($lines);
            return;
        }
        foreach ($lines as $line) {
            $ctx->out->print($line . "\n");
        }
    }

    public static function apply(Ctx $ctx, string $id, bool $dryRun, bool $printCommands): void
    {
        $planned = self::plan($ctx, $id);
        $answer = $ctx->call(Request::json(Routes::fixed('canvas.dry_run'), [], ['ops' => $planned['ops']]));
        $verdicts = Json::items($answer, 'verdicts');
        if ($dryRun) {
            $ctx->out->json ? $ctx->out->jsonValue($answer) : $ctx->out->list($verdicts, [['HANDLE', 'handle'], ['KIND', 'kind'], ['OP', 'op'], ['VERDICT', 'verdict'], ['CODE', 'code']]);
            if (Json::get($answer, 'ok') === false) {
                throw self::refused('the dry-run refused at least one op');
            }
            return;
        }
        try {
            $steps = Steps::of($planned['ops'], $verdicts, $planned['live'], $planned['kept']);
        } catch (Stop $stop) {
            throw self::stopFail($stop);
        }
        $manifests = self::writeManifests($planned['ops']);
        if ($printCommands) {
            // The printed lines name these files, so they outlive the run.
            self::printCommands($ctx, $steps, $manifests);
            return;
        }
        try {
            Execute::run($ctx, $planned, $steps, $manifests);
        } finally {
            array_map('unlink', array_filter($manifests, 'is_file'));
        }
    }
}
