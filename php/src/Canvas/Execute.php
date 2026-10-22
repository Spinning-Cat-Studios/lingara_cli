<?php

declare(strict_types=1);

namespace Lingara\Cli\Canvas;

use Lingara\Cli\Core\Assets;
use Lingara\Cli\Core\Ctx;
use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;
use Lingara\Cli\Core\Json;
use Lingara\Cli\Core\TableRender;
use Lingara\Cli\Http\Request;
use Lingara\Cli\Http\Routes;
use Lingara\Cli\Platform\Kind;
use Lingara\Cli\Platform\Objects;

/**
 * D7 steps 4–7. Each step's argv is dispatched **in-process** through the
 * app's `run`, the entry script's own entry point, with `--json`, so no
 * credential crosses a process boundary and no second token exchange
 * happens. Apply records each object's `runtime_id` (a secret's id), never
 * its platform object id, then activates `complete`, or `partial` at the
 * first failure.
 */
final class Execute
{
    /** A `runtime_id` still `null` is re-read once a second, ten times (D7). */
    private const PUBLISH_POLLS = 10;

    /** @var array<string, string> handle → the runtime id recorded for it */
    private array $recorded;

    /** @var array<string, string> handle → the platform object id an earlier step made */
    private array $made = [];

    /** @var array<string, string> handle → the secret id its create minted */
    private array $minted = [];

    /** @var list<array{handle: string, id: mixed, value: mixed}> */
    private array $secrets = [];

    /** @var list<list<string>> */
    private array $rows = [];

    /**
     * @param array{id: string, draft_version: mixed, kept: array<string, string>} $planned
     * @param array<string, string> $manifests handle → the file its `--manifest` names
     */
    private function __construct(private readonly Ctx $ctx, private readonly array $planned, private readonly array $manifests)
    {
        $this->recorded = $planned['kept'];
    }

    /**
     * @param array{id: string, draft_version: mixed, kept: array<string, string>} $planned
     * @param list<array<string, mixed>> $steps
     * @param array<string, string> $manifests
     */
    public static function run(Ctx $ctx, array $planned, array $steps, array $manifests): void
    {
        $run = new self($ctx, $planned, $manifests);
        foreach ($steps as $done => $step) {
            try {
                $run->step($step);
            } catch (\Throwable $e) {
                $fail = Fail::of($e);
                $saved = $run->activate('partial');
                $run->report('partial');
                if ($saved !== null) {
                    $fail->hint("the partial record was not saved: {$saved->getMessage()} ({$saved->errorCode})");
                }
                throw $fail->hint(Assets::message('canvas_apply_partial', ['done' => (string) $done, 'total' => (string) count($steps), 'id' => $planned['id']]));
            }
        }
        $saved = $run->activate('complete');
        if ($saved !== null) {
            throw $saved;
        }
        $run->report('complete');
    }

    /** @param array<string, mixed> $step */
    private function step(array $step): void
    {
        $handle = (string) $step['handle'];
        if ($step['records'] === 'client_secret') {
            $from = (string) ($step['from'] ?? '');
            $id = $this->minted[$from] ?? throw Fail::failure("{$from} minted no secret");
            foreach ($this->secrets as $i => $secret) {
                if ($secret['handle'] === $from) {
                    $this->secrets[$i]['handle'] = $handle;
                }
            }
            $this->recorded[$handle] = $id;
        } elseif ($step['argv'] !== []) {
            /** @var list<array<string, string>> $argv */
            $argv = $step['argv'];
            $this->record($step, $this->invoke($handle, $this->resolve($argv)));
        }
        $this->rows[] = [$handle, (string) $step['kind'], (string) $step['action'], $this->recorded[$handle] ?? '-'];
    }

    /** @param array<string, mixed> $step */
    private function record(array $step, mixed $answer): void
    {
        $handle = (string) $step['handle'];
        if ($step['records'] === 'runtime_id') {
            $this->made[$handle] = Json::str($answer, 'id');
            $kind = ['client' => Kind::oauthClient(), 'webhook' => Kind::webhookOutbound()][$step['kind']] ?? Kind::app();
            $this->recorded[$handle] = $this->runtimeId($kind, Json::str($answer, 'id'), $answer);
        } elseif ($step['records'] === 'secret_id') {
            $this->recorded[$handle] = Json::str($answer, 'secret_id');
        }
    }

    /**
     * @param list<array<string, string>> $argv
     * @return list<string>
     */
    private function resolve(array $argv): array
    {
        return array_map(function (array $p): string {
            $word = $p['text'] ?? (isset($p['made']) ? ($this->made[$p['made']] ?? null) : ($this->manifests[$p['manifest'] ?? ''] ?? null));
            return $word ?? throw Fail::failure(($p['made'] ?? '') . ' was not made before it was needed');
        }, $argv);
    }

    /**
     * One inner verb; its JSON, any secret it revealed kept even when it failed part-way.
     *
     * @param list<string> $argv
     */
    private function invoke(string $handle, array $argv): mixed
    {
        $outcome = ($this->ctx->app->run)([...$argv, '--json', '--profile', $this->ctx->profile], static function (string $text): void {
        });
        $answer = Json::decode($outcome->stdout) ?? new \stdClass();
        $nested = Json::get($answer, 'secret');
        $secret = $nested instanceof \stdClass ? [Json::get($nested, 'id'), Json::get($nested, 'value')] : null;
        if ($secret === null && is_string(Json::get($answer, 'secret_id'))) {
            $secret = [Json::get($answer, 'secret_id'), Json::get($answer, 'secret')];
        }
        if ($secret !== null) {
            $this->minted[$handle] = is_string($secret[0]) ? $secret[0] : '';
            $this->secrets[] = ['handle' => $handle, 'id' => $secret[0], 'value' => $secret[1]];
        }
        if ($outcome->fail !== null) {
            throw $outcome->fail;
        }
        return $answer;
    }

    /** The object's `runtime_id`, set once publishing has projected (16.10.26v §5). */
    private function runtimeId(Kind $k, string $platformId, mixed $answer): string
    {
        $current = $answer;
        for ($i = 0; $i < self::PUBLISH_POLLS && !is_string(Json::get($current, 'runtime_id')); $i++) {
            $this->ctx->app->http->clock->sleep(1);
            $current = Objects::get($this->ctx, $k, $platformId);
        }
        return Json::opt($current, 'runtime_id')
            ?? throw new Fail(ExitCode::Unavailable, 'object_projection_pending', "{$k->word} {$platformId} is still publishing; nothing after it was applied");
    }

    /** `{draft_version, outcome, objects}`: runtime ids only (16.10.26x D4). Null when it saved; the refusal when it did not. */
    private function activate(string $outcome): ?Fail
    {
        $body = ['draft_version' => $this->planned['draft_version'], 'outcome' => $outcome, 'objects' => $this->objects()];
        try {
            $this->ctx->call(Request::json(Routes::fixed('canvas.activate'), ['id' => $this->planned['id']], $body));
        } catch (Fail $fail) {
            if ($fail->errorCode === 'canvas_conflict') {
                $fail->hint("the canvas changed while applying; run `lingara canvas get {$this->planned['id']}`");
            }
            return $fail;
        }
        return null;
    }

    private function objects(): \stdClass
    {
        $objects = new \stdClass();
        foreach ($this->recorded as $handle => $id) {
            $objects->{$handle} = (object) ['id' => $id];
        }
        return $objects;
    }

    /** D7 step 7: `HANDLE  KIND  ACTION  ID`, then every new secret, once. */
    private function report(string $status): void
    {
        $out = $this->ctx->out;
        if ($out->json) {
            $out->jsonValue(['objects' => $this->objects(), 'secrets' => array_map(static fn (array $s): \stdClass => (object) $s, $this->secrets), 'status' => $status]);
        } else {
            $text = TableRender::render(['HANDLE', 'KIND', 'ACTION', 'ID'], $this->rows);
            if ($this->secrets !== []) {
                $rows = array_map(static fn (array $s): array => [$s['handle'], is_string($s['id']) ? $s['id'] : '', is_string($s['value']) ? $s['value'] : ''], $this->secrets);
                $text .= "\n" . TableRender::render(['HANDLE', 'SECRET_ID', 'SECRET'], $rows);
            }
            $out->print($text);
        }
        if ($this->secrets !== []) {
            $out->note('Each secret is shown once: store them now. They cannot be read again.');
        }
    }
}
