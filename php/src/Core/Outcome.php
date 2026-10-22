<?php

declare(strict_types=1);

namespace Lingara\Cli\Core;

/** What one run of argv came to: its exit, its buffered stdout and, on a refusal, why. */
final class Outcome
{
    public function __construct(public readonly ExitCode $exit, public readonly string $stdout, public readonly ?Fail $fail = null)
    {
    }
}
