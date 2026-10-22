<?php

declare(strict_types=1);

namespace Lingara\Cli\Canvas;

/** Why apply stops after the dry-run and before any write (D7 step 3): every refusal, or a `would_replace`. */
final class Stop extends \RuntimeException
{
    /** @param list<array{string, string, string}> $refused `[handle, code, error]` */
    public function __construct(public readonly array $refused, public readonly ?string $replace = null)
    {
        parent::__construct($replace === null ? 'refused' : 'would_replace');
    }
}
