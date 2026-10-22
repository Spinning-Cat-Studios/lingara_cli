<?php

declare(strict_types=1);

namespace Lingara\Cli\Canvas;

use Lingara\Cli\Core\Json;

/**
 * D7 steps 3–4: the dry-run's verdicts over the plan become this CLI's own
 * command lines. A `refused` verdict stops apply with every refusal named; a
 * `would_replace`, which the token door no longer sends, stops it before any
 * write. A `would_update` webhook carries exactly the flags its `changes`
 * name: `--url`/`--event` (a new version that keeps the endpoint and its
 * secret) and `--enable` (no builder spec disables one).
 *
 * A word of an argv is `['text' => …]`, `['made' => handle]` (an id an
 * earlier step makes) or `['manifest' => handle]` (an app op's manifest
 * file). A step records `runtime_id`, `secret_id`, `client_secret` (the
 * secret its client's create minted, `from` naming that client) or
 * `unchanged`.
 *
 * @phpstan-type Part array{text?: string, made?: string, manifest?: string}
 * @phpstan-type Step array{handle: string, kind: string, action: string, argv: list<array<string, string>>, records: string, from?: string}
 */
final class Steps
{
    /**
     * Every step, or why apply must stop before writing anything.
     *
     * @param list<array<string, mixed>> $ops
     * @param list<mixed> $verdicts
     * @param array<string, list<array{id: string, runtime_id: string, parent_id: string}>> $live
     * @param array<string, string> $kept
     * @return list<array<string, mixed>>
     */
    public static function of(array $ops, array $verdicts, array $live, array $kept): array
    {
        $refused = [];
        foreach ($verdicts as $v) {
            if (Json::get($v, 'verdict') === 'refused') {
                $refused[] = [Json::str($v, 'handle'), Json::str($v, 'code'), Json::str($v, 'error')];
            }
        }
        if ($refused !== []) {
            throw new Stop($refused);
        }
        foreach ($verdicts as $v) {
            if (Json::get($v, 'verdict') === 'would_replace') {
                throw new Stop([], Json::str($v, 'handle'));
            }
        }
        $context = ['live' => $live, 'kept' => $kept, 'ops' => $ops];
        return array_map(static fn (array $op): array => self::step($op, self::verdictFor($verdicts, (string) $op['handle']), $context), $ops);
    }

    /** @param list<mixed> $verdicts */
    private static function verdictFor(array $verdicts, string $handle): mixed
    {
        foreach ($verdicts as $v) {
            if (Json::get($v, 'handle') === $handle) {
                return $v;
            }
        }
        return new \stdClass();
    }

    /**
     * The client an op points at: made in this run, or a recorded one's platform object id.
     *
     * @param array{live: array<string, list<array{id: string, runtime_id: string, parent_id: string}>>, kept: array<string, string>, ops: list<array<string, mixed>>} $c
     * @return array<string, string>
     */
    private static function clientRef(string $handle, array $c): array
    {
        foreach ($c['ops'] as $o) {
            if ($o['handle'] === $handle && $o['op'] === 'create') {
                return ['made' => $handle];
            }
        }
        $known = isset($c['kept'][$handle]) ? Plan::platformId($c['live'], 'client', $c['kept'][$handle]) : null;
        return $known !== null ? ['text' => $known] : ['made' => $handle];
    }

    /**
     * @param array<string, mixed> $op
     * @param array{live: array<string, list<array{id: string, runtime_id: string, parent_id: string}>>, kept: array<string, string>, ops: list<array<string, mixed>>} $c
     * @return array<string, mixed>
     */
    private static function step(array $op, mixed $verdict, array $c): array
    {
        $changes = Json::strings(Json::get($verdict, 'changes'));
        $base = ['handle' => (string) $op['handle'], 'kind' => (string) $op['kind'], 'action' => (string) $op['op']];
        if ($op['op'] === 'update' && $changes === []) {
            return [...$base, 'action' => 'unchanged', 'argv' => [], 'records' => 'unchanged'];
        }
        $ownId = isset($op['id']) ? Plan::platformId($c['live'], (string) $op['kind'], (string) $op['id']) : null;
        $client = isset($op['client']['handle']) ? self::clientRef((string) $op['client']['handle'], $c) : ['made' => ''];
        $pieces = [
            'body' => is_array($op['body'] ?? null) ? $op['body'] : [],
            'own' => $ownId === null ? ['made' => (string) $op['handle']] : ['text' => $ownId],
            'client' => $client,
            'changes' => $changes,
        ];
        return [...$base, ...self::argvFor($op, $verdict, $pieces)];
    }

    /**
     * @param array<string, mixed> $op
     * @param array{body: array<string, mixed>, own: array<string, string>, client: array<string, string>, changes: list<string>} $p
     * @return array{argv: list<array<string, string>>, records: string, from?: string}
     */
    private static function argvFor(array $op, mixed $verdict, array $p): array
    {
        $created = $op['op'] === 'create' ? 'runtime_id' : 'unchanged';
        if ($op['kind'] === 'secret') {
            if (Json::get($verdict, 'note') === 'minted_with_client') {
                return ['argv' => [], 'records' => 'client_secret', 'from' => (string) ($op['client']['handle'] ?? '')];
            }
            return ['argv' => [self::t('oauth_client'), self::t('secret'), self::t('create'), $p['client']], 'records' => 'secret_id'];
        }
        $argv = match ($op['kind']) {
            'client' => self::clientArgv($op, $p),
            'webhook' => $op['op'] === 'create' ? self::webhookCreate($p) : self::webhookUpdate($p),
            default => self::appArgv($op, $p),
        };
        return ['argv' => $argv, 'records' => $created];
    }

    /** @return array<string, string> */
    private static function t(string $text): array
    {
        return ['text' => $text];
    }

    /**
     * @param list<string> $values
     * @return list<array<string, string>>
     */
    private static function repeated(string $flag, array $values): array
    {
        $out = [];
        foreach ($values as $value) {
            array_push($out, self::t($flag), self::t($value));
        }
        return $out;
    }

    /**
     * @param array<string, mixed> $op
     * @param array{body: array<string, mixed>, own: array<string, string>, client: array<string, string>, changes: list<string>} $p
     * @return list<array<string, string>>
     */
    private static function clientArgv(array $op, array $p): array
    {
        $head = $op['op'] === 'create' ? [self::t('oauth_client'), self::t('create')] : [self::t('oauth_client'), self::t('update'), $p['own']];
        $name = is_string($p['body']['name'] ?? null) ? $p['body']['name'] : '';
        return [
            ...$head, self::t('--name'), self::t($name),
            ...self::repeated('--scope', Json::strings($p['body']['scopes'] ?? null)),
            ...self::repeated('--redirect-uri', Json::strings($p['body']['redirect_uris'] ?? null)),
        ];
    }

    /**
     * @param array{body: array<string, mixed>, own: array<string, string>, client: array<string, string>, changes: list<string>} $p
     * @return list<array<string, string>>
     */
    private static function webhookCreate(array $p): array
    {
        $url = is_string($p['body']['url'] ?? null) ? $p['body']['url'] : '';
        $head = [self::t('webhook'), self::t('outbound'), self::t('create'), self::t('--client'), $p['client'], self::t('--url'), self::t($url)];
        return [...$head, ...self::repeated('--event', Json::strings($p['body']['event_types'] ?? null))];
    }

    /**
     * @param array{body: array<string, mixed>, own: array<string, string>, client: array<string, string>, changes: list<string>} $p
     * @return list<array<string, string>>
     */
    private static function webhookUpdate(array $p): array
    {
        $argv = [self::t('webhook'), self::t('outbound'), self::t('update'), $p['own']];
        if (in_array('url', $p['changes'], true)) {
            array_push($argv, self::t('--url'), self::t(is_string($p['body']['url'] ?? null) ? $p['body']['url'] : ''));
        }
        if (in_array('event_types', $p['changes'], true)) {
            array_push($argv, ...self::repeated('--event', Json::strings($p['body']['event_types'] ?? null)));
        }
        if (in_array('enabled', $p['changes'], true)) {
            $argv[] = self::t('--enable');
        }
        return $argv;
    }

    /**
     * @param array<string, mixed> $op
     * @param array{body: array<string, mixed>, own: array<string, string>, client: array<string, string>, changes: list<string>} $p
     * @return list<array<string, string>>
     */
    private static function appArgv(array $op, array $p): array
    {
        $manifest = ['manifest' => (string) $op['handle']];
        if ($op['op'] === 'create') {
            $name = Spec::appName($p['body'], (string) $op['handle']);
            return [self::t('apps'), self::t('create'), self::t($name), self::t('--client'), $p['client'], self::t('--manifest'), $manifest];
        }
        return [self::t('apps'), self::t('update'), $p['own'], self::t('--manifest'), $manifest];
    }

    /** POSIX single quotes, only where a word needs them. */
    public static function quote(string $word): string
    {
        return $word !== '' && preg_match('/^[A-Za-z0-9\-_.\/:@=,+]+$/', $word) === 1 ? $word : "'" . str_replace("'", "'\\''", $word) . "'";
    }

    /**
     * One argv as a shell line, ids not yet made as `<handle>`.
     *
     * @param list<array<string, string>> $argv
     * @param \Closure(string): string $manifest
     */
    public static function shellLine(array $argv, \Closure $manifest): string
    {
        $words = array_map(static fn (array $p): string => match (true) {
            isset($p['text']) => self::quote($p['text']),
            isset($p['made']) => self::quote("<{$p['made']}>"),
            default => self::quote($manifest($p['manifest'] ?? '')),
        }, $argv);
        return 'lingara ' . implode(' ', $words);
    }
}
