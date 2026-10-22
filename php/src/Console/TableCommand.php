<?php

declare(strict_types=1);

namespace Lingara\Cli\Console;

use Lingara\Cli\Table\Topics;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputArgument;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;

/**
 * One symfony command, registered from its row of the kit-local table
 * (ADR 16.10.26af D2): names and modes go into symfony's definition, which
 * parses argv; `type`, `values` and an option's `required` stay in the row,
 * which `Validator` enforces and `Tree` reports. What the command does is
 * its row's handler, run by `Runner`, so no command maps errors by itself.
 */
final class TableCommand extends Command
{
    /** @param array<string, mixed> $row */
    public function __construct(string $path, public readonly array $row, private readonly Runner $runner)
    {
        parent::__construct($path);
    }

    protected function configure(): void
    {
        $this->setDescription(is_string($this->row['help'] ?? null) ? $this->row['help'] : '');
        $this->setHidden(($this->row['hidden'] ?? false) === true);
        $alias = self::alias((string) $this->getName());
        if ($alias !== null) {
            $this->setAliases([$alias]);
        }
        $this->inputs();
    }

    /** The row's args and flags, as symfony arguments and options: names and modes only. */
    private function inputs(): void
    {
        foreach (self::rows($this->row['args'] ?? []) as $arg) {
            $mode = ($arg['required'] ?? false) === true ? InputArgument::REQUIRED : InputArgument::OPTIONAL;
            $this->addArgument((string) $arg['name'], $mode);
        }
        foreach (self::rows($this->row['flags'] ?? []) as $flag) {
            $this->addOption((string) $flag['name'], self::short($flag), self::mode($flag), (string) ($flag['help'] ?? ''));
        }
    }

    /** `client create` for `oauth_client create`: the topic's alias spelled into the path. */
    private static function alias(string $path): ?string
    {
        foreach (explode(' ', $path) as $i => $word) {
            $topic = implode(' ', array_slice(explode(' ', $path), 0, $i + 1));
            foreach (Topics::aliases($topic) as $alias) {
                $words = explode(' ', $path);
                $words[$i] = $alias;
                return implode(' ', $words);
            }
        }
        return null;
    }

    /**
     * @param mixed $rows
     * @return list<array<string, mixed>>
     */
    public static function rows(mixed $rows): array
    {
        return is_array($rows) ? array_values(array_filter($rows, 'is_array')) : [];
    }

    /** @param array<string, mixed> $flag */
    private static function short(array $flag): ?string
    {
        return is_string($flag['short'] ?? null) ? $flag['short'] : null;
    }

    /** @param array<string, mixed> $flag */
    private static function mode(array $flag): int
    {
        if ($flag['type'] === 'bool') {
            return InputOption::VALUE_NONE;
        }
        $repeatable = ($flag['repeatable'] ?? false) === true ? InputOption::VALUE_IS_ARRAY : 0;
        return InputOption::VALUE_REQUIRED | $repeatable;
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        return $this->runner->execute($this, $input);
    }
}
