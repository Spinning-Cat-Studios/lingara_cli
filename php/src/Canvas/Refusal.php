<?php

declare(strict_types=1);

namespace Lingara\Cli\Canvas;

/**
 * Why the planner refuses a canvas, before the dry-run and with zero writes
 * (ADR 16.10.26ad D7 step 2): `script`, `fields`, `no_client`, `cycle`, or
 * `ephemeral` for an app node whose manifest renders from a quick tunnel or
 * `dev.invalid` (16.10.26ae D10 row 15).
 */
final class Refusal extends \RuntimeException
{
    public function __construct(public readonly string $why, public readonly string $handle, string $detail)
    {
        parent::__construct($detail);
    }
}
