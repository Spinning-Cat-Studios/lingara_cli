<?php

declare(strict_types=1);

namespace Lingara\Cli\Console;

use Lingara\Cli\Table\Topics;
use Symfony\Component\Console\Application as SymfonyApplication;

/**
 * `--help` on the root or a topic: the commands and topics directly under
 * it, each with its summary. symfony/console has no topic model to describe
 * (its `list` command is gone, ADR 16.10.26af D9), and the layout of help is
 * each kit's own (D1's "May differ").
 */
final class TopicHelp
{
    public static function render(SymfonyApplication $app, string $topic): string
    {
        $prefix = $topic === '' ? '' : $topic . ' ';
        $rows = [];
        foreach (Topics::TOPICS as $path => $row) {
            if (self::child($path, $prefix)) {
                $rows[$path] = $row['help'];
            }
        }
        foreach ($app->all() as $name => $command) {
            if ($command->getName() === $name && !$command->isHidden() && self::child($name, $prefix)) {
                $rows[$name] = $command->getDescription();
            }
        }
        $width = max(array_map('strlen', array_keys($rows ?: ['' => ''])));
        $text = 'Usage: lingara ' . $prefix . "<command> [options]\n\nCommands:\n";
        foreach ($rows as $name => $help) {
            $text .= '  ' . str_pad((string) $name, $width) . "  {$help}\n";
        }
        return $text . "\nGlobal options: --json, --profile <name>, -h/--help\n";
    }

    /** Whether `$path` sits one word below `$prefix`. */
    private static function child(string $path, string $prefix): bool
    {
        return str_starts_with($path, $prefix) && !str_contains(substr($path, strlen($prefix)), ' ');
    }
}
