<?php

declare(strict_types=1);

namespace Lingara\Cli\Console;

use Lingara\Cli\Core\Fail;
use Lingara\Cli\Table\Topics;
use Symfony\Component\Console\Application as SymfonyApplication;

/**
 * argv resolved space-separated against the registered tree, before
 * dispatch (ADR 16.10.26af D9): `webhook inbound create --name x` is the
 * command `webhook inbound create` and the rest. The leading words are
 * matched longest first, exactly, so a command prefix (`apps li`), a
 * colon-namespaced spelling (`app:li`) or `list` is never a command.
 */
final class Resolver
{
    /**
     * The command's name and the argv after it, or null when the leading
     * words name no command.
     *
     * @param list<string> $argv
     * @return array{string, list<string>}|null
     */
    public static function command(SymfonyApplication $app, array $argv): ?array
    {
        $words = self::words($argv);
        for ($n = count($words); $n > 0; $n--) {
            $name = implode(' ', array_slice($words, 0, $n));
            if ($app->has($name)) {
                return [$name, array_slice($argv, $n)];
            }
        }
        return null;
    }

    /**
     * The topic the leading words name (`''` for none: the root), refused
     * when a word after it names nothing.
     *
     * @param list<string> $argv
     */
    public static function topic(array $argv): string
    {
        $words = self::words($argv);
        $found = '';
        $used = 0;
        foreach ($words as $i => $word) {
            $path = $found === '' ? $word : "{$found} {$word}";
            $topic = Topics::canonical($path) ?? $path;
            if (!isset(Topics::TOPICS[$topic])) {
                break;
            }
            [$found, $used] = [$topic, $i + 1];
        }
        if ($used < count($words)) {
            $where = $found === '' ? '' : " under {$found}";
            throw Fail::usage("{$words[$used]} is not a lingara command{$where}");
        }
        return $found;
    }

    /**
     * @param list<string> $argv
     * @return list<string> the words before the first option
     */
    private static function words(array $argv): array
    {
        $words = [];
        foreach ($argv as $token) {
            if (str_starts_with($token, '-')) {
                break;
            }
            $words[] = $token;
        }
        return $words;
    }
}
