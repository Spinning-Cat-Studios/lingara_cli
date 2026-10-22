<?php

declare(strict_types=1);

namespace Lingara\Cli\Core;

/** The process's own stdin and stderr (ADR 16.10.26af D2's TTY test). */
final class StdioTerminal implements Terminal
{
    public function interactive(): bool
    {
        return stream_isatty(STDIN) && stream_isatty(STDERR);
    }

    public function readLine(): ?string
    {
        $line = fgets(STDIN);
        return $line === false ? null : $line;
    }
}
