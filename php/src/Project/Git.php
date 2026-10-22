<?php

declare(strict_types=1);

namespace Lingara\Cli\Project;

use Symfony\Component\Process\Process;

/**
 * D4's two git questions (ADR 16.10.26ae): is a file tracked (a warning),
 * and does git ignore a path that would hold secrets (a refusal). Outside a
 * work tree, or with no `git` on PATH, there is no index to commit a file
 * into, so both answers are the permissive ones and the checks are skipped.
 */
final class Git
{
    /** @param list<string> $args */
    private static function git(string $dir, array $args): bool
    {
        try {
            return (new Process(['git', '-C', $dir, ...$args]))->run() === 0;
        } catch (\Throwable) {
            return false;
        }
    }

    public static function inWorkTree(string $dir): bool
    {
        return self::git($dir, ['rev-parse', '--is-inside-work-tree']);
    }

    /** Whether git tracks `$path`; false outside a work tree. */
    public static function tracked(string $path): bool
    {
        $dir = dirname($path);
        return self::inWorkTree($dir) && self::git($dir, ['ls-files', '--error-unmatch', '--', basename($path)]);
    }

    /** Whether git ignores `$path`; true outside a work tree, where nothing can be committed. */
    public static function ignored(string $path): bool
    {
        $dir = dirname($path);
        return !self::inWorkTree($dir) || self::git($dir, ['check-ignore', '-q', '--', basename($path)]);
    }
}
