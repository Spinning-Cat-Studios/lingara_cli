<?php

declare(strict_types=1);

namespace Lingara\Cli\Table;

/**
 * The one kit-local arg/type table (ADR 16.10.26af D2): every command's
 * path, summary, handler, positional args and flags. Registration builds each
 * symfony command from its row and `__tree` reads the same row back, so the
 * projection is never a second hand-written tree. `make check-tree-php`
 * diffs it against spec/cli.schema.json.
 */
final class Commands
{
    /** @return array<string, array<string, mixed>> path → row, in the schema's order */
    public static function all(): array
    {
        return array_merge(SessionTable::COMMANDS, AppsTable::COMMANDS, ObjectsTable::COMMANDS, WebhookTable::COMMANDS, CanvasTable::COMMANDS);
    }
}
