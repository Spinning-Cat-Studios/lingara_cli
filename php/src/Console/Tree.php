<?php

declare(strict_types=1);

namespace Lingara\Cli\Console;

use Lingara\Cli\Table\Flags;
use Symfony\Component\Console\Application as SymfonyApplication;
use Symfony\Component\Console\Input\InputArgument;
use Symfony\Component\Console\Input\InputOption;

/**
 * `lingara __tree` (ADR 16.10.26ab D4; 16.10.26af D2, AC6): the registered
 * tree, read back from symfony/console's own model, never written twice.
 * Command names, aliases, and each `InputArgument`'s and `InputOption`'s
 * name and mode come from symfony; `type`, `values` and an option's
 * `required` from the kit-local table the command was registered from,
 * because symfony's inputs are untyped strings. A command is dangerous
 * exactly when it carries the confirm group (16.10.26ab D2.4). `oauth_client`'s
 * `client` alias is registered on each command under it and folded back here
 * onto the one aliased topic, as the TypeScript kit's tree does.
 *
 * @phpstan-type Node array{name: string, aliases: list<string>, commands: list<array<string, mixed>>, topics: list<array<string, mixed>>}
 */
final class Tree
{
    /** @return array<string, mixed> */
    public static function of(SymfonyApplication $app): array
    {
        $commands = array_filter(
            $app->all(),
            static fn (mixed $c, string $name): bool => $c instanceof TableCommand && !$c->isHidden() && $c->getName() === $name,
            ARRAY_FILTER_USE_BOTH,
        );
        $aliases = [];
        foreach ($commands as $name => $command) {
            self::fold($aliases, (string) $name, array_values(array_filter($command->getAliases(), 'is_string')));
        }
        $root = ['name' => 'lingara', 'aliases' => [], 'commands' => [], 'topics' => []];
        foreach ($commands as $name => $command) {
            $words = explode(' ', (string) $name);
            $node = self::command((string) array_pop($words), $command);
            self::insert($root, $words, '', $node, $aliases);
        }
        $root['global_flags'] = self::globals($app);
        return $root;
    }

    /**
     * The topic alias each command alias spells: where its words first
     * differ from the name's (`client create` names topic `oauth_client`).
     *
     * @param array<string, list<string>> $aliases topic path → its aliases
     * @param list<string> $commandAliases
     */
    private static function fold(array &$aliases, string $name, array $commandAliases): void
    {
        $words = explode(' ', $name);
        foreach ($commandAliases as $alias) {
            foreach (explode(' ', $alias) as $i => $word) {
                $topic = implode(' ', array_slice($words, 0, $i + 1));
                if ($word !== ($words[$i] ?? null) && !in_array($word, $aliases[$topic] ?? [], true)) {
                    $aliases[$topic][] = $word;
                }
            }
        }
    }

    /**
     * @param array<string, mixed> $at
     * @param list<string> $words the topic path still to descend
     * @param array<string, mixed> $node
     * @param array<string, list<string>> $aliases
     */
    private static function insert(array &$at, array $words, string $path, array $node, array $aliases): void
    {
        if ($words === []) {
            $at['commands'][] = $node;
            return;
        }
        $word = (string) array_shift($words);
        $path = $path === '' ? $word : "{$path} {$word}";
        foreach ($at['topics'] as &$topic) {
            if ($topic['name'] === $word) {
                self::insert($topic, $words, $path, $node, $aliases);
                return;
            }
        }
        unset($topic);
        $new = ['name' => $word, 'aliases' => $aliases[$path] ?? [], 'commands' => [], 'topics' => []];
        self::insert($new, $words, $path, $node, $aliases);
        $at['topics'][] = $new;
    }

    /** @return array<string, mixed> */
    private static function command(string $name, TableCommand $command): array
    {
        $definition = $command->getNativeDefinition();
        $args = [];
        foreach ($definition->getArguments() as $arg) {
            $args[] = self::argument($arg, self::row($command->row['args'] ?? [], $arg->getName()));
        }
        $flags = [];
        foreach ($definition->getOptions() as $option) {
            $flags[] = self::option($option, self::row($command->row['flags'] ?? [], $option->getName()));
        }
        $dangerous = $definition->hasOption('confirm');
        return ['name' => $name, 'aliases' => [], 'dangerous' => $dangerous, 'args' => $args, 'flags' => $flags];
    }

    /**
     * @param array<string, mixed> $row
     * @return array<string, mixed>
     */
    private static function argument(InputArgument $arg, array $row): array
    {
        return ['name' => $arg->getName(), 'type' => $row['type'] ?? 'string', 'required' => $arg->isRequired(), 'repeatable' => $arg->isArray()];
    }

    /**
     * @param array<string, mixed> $row
     * @return array<string, mixed>
     */
    private static function option(InputOption $option, array $row): array
    {
        $out = [
            'name' => $option->getName(),
            'type' => $option->acceptValue() ? ($row['type'] ?? 'string') : 'bool',
            'required' => ($row['required'] ?? false) === true,
            'repeatable' => $option->isArray(),
        ];
        if ($option->getShortcut() !== null) {
            $out['short'] = $option->getShortcut();
        }
        if ($out['type'] === 'enum' && is_array($row['values'] ?? null)) {
            $out['values'] = $row['values'];
        }
        return $out;
    }

    /** @return array<string, mixed> the table row named `$name`, or none */
    private static function row(mixed $rows, string $name): array
    {
        foreach (TableCommand::rows($rows) as $row) {
            if ($row['name'] === $name) {
                return $row;
            }
        }
        return [];
    }

    /** @return list<array<string, mixed>> `--json` and `--profile`, from the application's definition */
    private static function globals(SymfonyApplication $app): array
    {
        $out = [];
        foreach ($app->getDefinition()->getOptions() as $option) {
            if ($option->getName() !== 'help') {
                $out[] = self::option($option, self::row(Flags::GLOBAL, $option->getName()));
            }
        }
        return $out;
    }
}
