<?php

declare(strict_types=1);

namespace Lingara\Cli\Console;

use Lingara\Cli\Table\Commands;
use Lingara\Cli\Table\Flags;
use Lingara\Cli\Version;
use Symfony\Component\Console\Application as SymfonyApplication;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Command\CompleteCommand;
use Symfony\Component\Console\Command\DumpCompletionCommand;
use Symfony\Component\Console\Command\HelpCommand;
use Symfony\Component\Console\Exception\CommandNotFoundException;
use Symfony\Component\Console\Input\ArrayInput;
use Symfony\Component\Console\Input\InputArgument;
use Symfony\Component\Console\Input\InputDefinition;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;

/**
 * symfony/console bent to the shared contract in one place (ADR 16.10.26af
 * D9). Its defaults would fail C2 cases, so this subclass:
 *
 * - keeps only `--help`/`-h` of the default options (no `-q -v -V -n
 *   --ansi`), adding the schema's two global flags;
 * - keeps only `help` and `completion` of the default commands (`list` is
 *   gone; `_complete` stays hidden, because `completion`'s script calls it);
 * - finds a command by its exact name or alias only, never an abbreviation
 *   such as `app:li`;
 * - runs only what `doRun` below does: no `--version` short-circuit (the
 *   runner prints the kit's own line), no "did you mean" prompt.
 *
 * Commands are named by their space-separated path (`webhook inbound
 * create`), which `Resolver` reads off argv before dispatch.
 */
final class Application extends SymfonyApplication
{
    public function __construct(Runner $runner)
    {
        parent::__construct('lingara', Version::VERSION . ' (php)');
        foreach (Commands::all() as $path => $row) {
            $this->addCommand(new TableCommand($path, $row, $runner));
        }
    }

    protected function getDefaultInputDefinition(): InputDefinition
    {
        $definition = new InputDefinition([
            new InputArgument('command', InputArgument::REQUIRED, 'The command to execute'),
            new InputOption('--help', '-h', InputOption::VALUE_NONE, 'Display help for the given command'),
        ]);
        foreach (Flags::GLOBAL as $flag) {
            $mode = $flag['type'] === 'bool' ? InputOption::VALUE_NONE : InputOption::VALUE_REQUIRED;
            $definition->addOption(new InputOption($flag['name'], null, $mode, $flag['help']));
        }
        return $definition;
    }

    /** @return list<Command> */
    protected function getDefaultCommands(): array
    {
        return [new HelpCommand(), new DumpCompletionCommand(), new CompleteCommand()];
    }

    /** Exact name or alias only: symfony's abbreviation and namespace matching are off. */
    public function find(string $name): Command
    {
        return $this->has($name) ? $this->get($name) : throw new CommandNotFoundException("{$name} is not a lingara command");
    }

    /** The command `Resolver` put first, or its help under `--help`/`-h`. */
    public function doRun(InputInterface $input, OutputInterface $output): int
    {
        $command = $this->find((string) $input->getFirstArgument());
        if ($input->hasParameterOption(['--help', '-h'], true)) {
            $help = $this->get('help');
            if ($help instanceof HelpCommand) {
                $help->setCommand($command);
            }
            $help->mergeApplicationDefinition();
            return $help->run(new ArrayInput([]), $output);
        }
        return $this->doRunCommand($command, $input, $output);
    }
}
