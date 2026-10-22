<?php

declare(strict_types=1);

namespace Lingara\Cli\Table;

use Lingara\Cli\Handlers\SessionHandlers;

/** The root commands (ADR 16.10.26ad D1) and the hidden `__tree` (16.10.26ab D4). */
final class SessionTable
{
    public const COMMANDS = [
        'login' => [
            'help' => 'Sign in through the browser, or with a device code.',
            'run' => [SessionHandlers::class, 'login'],
            'flags' => [['name' => 'device', 'type' => 'bool', 'help' => 'Sign in with a code shown here, for a machine with no browser']],
        ],
        'logout' => ['help' => 'Revoke and forget the stored credentials.', 'run' => [SessionHandlers::class, 'logout']],
        'whoami' => ['help' => 'Show the developer the credentials belong to.', 'run' => [SessionHandlers::class, 'whoami']],
        '__tree' => ['help' => 'The registered command tree as JSON, for make check-tree.', 'run' => [SessionHandlers::class, 'tree'], 'hidden' => true],
    ];
}
