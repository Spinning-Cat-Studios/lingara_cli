<?php

declare(strict_types=1);

namespace Lingara\Cli\Platform;

use Lingara\Cli\Core\Ctx;
use Lingara\Cli\Core\Fail;
use Lingara\Cli\Core\Json;
use Lingara\Cli\Core\TableRender;

/**
 * A create that may take several calls (ADR 16.10.26ad D1, D2). It ends with
 * one `<word>.get`, printed with any secret it minted. Failing part-way, it
 * still reveals that secret on stdout and names every object it made on
 * stderr, then exits with the failing call's code. Nothing is rolled back.
 */
final class Creation
{
    public const SHOWN_ONCE = 'The secret is shown once: store it now. It cannot be read again.';

    /** @var list<array{kind: string, id: string}> */
    public array $made = [];

    /** @var array{id: mixed, value: mixed}|null the D6 member a create's JSON gains */
    public ?array $secret = null;

    /** One object: created, named as made, then published when private. */
    public function object(Ctx $ctx, Kind $k, string $name, ?string $parent, mixed $body): mixed
    {
        $created = Objects::create($ctx, $k, $name, $parent, $body);
        $this->made[] = ['kind' => $k->word, 'id' => Json::str($created, 'id')];
        Objects::publishIfPrivate($ctx, $k, $created, Json::str(Json::get($created, 'version'), 'semver'));
        return $created;
    }

    /** The one-time secret. `409 object_projection_pending`: the client is still publishing (16.10.26v §5). */
    public function mint(Ctx $ctx, Kind $k, string $id): void
    {
        try {
            $minted = Objects::secretCreate($ctx, $k, $id);
        } catch (Fail $fail) {
            if ($fail->errorCode === 'object_projection_pending') {
                $fail->hint("the client is still publishing; run `lingara client secret create {$id}` in a moment");
            }
            throw $fail;
        }
        $this->secret = ['id' => Json::get($minted, 'secret_id'), 'value' => Json::get($minted, 'secret')];
    }

    /**
     * Runs `$steps`, then the final read and its printing, or the failure
     * with what was made.
     *
     * @param \Closure(): array{Kind, string} $steps the kind and id to read at the end
     */
    public function run(Ctx $ctx, \Closure $steps): mixed
    {
        try {
            [$k, $id] = $steps();
            $obj = Objects::get($ctx, $k, $id);
        } catch (Fail $fail) {
            $this->print($ctx, null);
            $fail->made = $this->made;
            throw $fail;
        }
        $this->print($ctx, $obj);
        return $obj;
    }

    private function print(Ctx $ctx, mixed $obj): void
    {
        if ($obj === null && $this->secret === null) {
            return;
        }
        if ($ctx->out->json) {
            $value = $obj instanceof \stdClass ? clone $obj : new \stdClass();
            if ($this->secret !== null) {
                $value->secret = (object) $this->secret;
            }
            $ctx->out->jsonValue($value);
        } else {
            $rows = $obj === null ? [] : TableRender::fields($obj);
            if ($this->secret !== null) {
                $rows[] = ['SECRET', TableRender::cell($this->secret['value'])];
            }
            $ctx->out->print(TableRender::render(['FIELD', 'VALUE'], $rows));
        }
        if ($this->secret !== null) {
            $ctx->out->note(self::SHOWN_ONCE);
        }
    }
}
