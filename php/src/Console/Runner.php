<?php

declare(strict_types=1);

namespace Lingara\Cli\Console;

use Lingara\Cli\Core\App;
use Lingara\Cli\Core\Ctx;
use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;
use Lingara\Cli\Core\Outcome;
use Lingara\Cli\Core\Output;
use Lingara\Cli\Store\ConfigDir;
use Lingara\Cli\Version;
use Symfony\Component\Console\Exception\ExceptionInterface;
use Symfony\Component\Console\Input\ArgvInput;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\BufferedOutput;

/**
 * One run: resolve argv to a command, let symfony/console parse it, pick the
 * profile and the API, run the handler, and turn the result into an exit
 * code. `run` is the one entry point, used by `bin/lingara` and, in-process,
 * by canvas apply's inner verbs (ADR 16.10.26ad D7 step 4), which share this
 * app's signed-in session. A parse exception is `usage` (2), never symfony's
 * exit 1 (ADR 16.10.26af D9).
 */
final class Runner
{
    public readonly Application $application;

    /** @var \Closure(string): void the current run's stderr */
    private \Closure $err;

    private ?Outcome $last = null;

    public function __construct(private readonly App $app)
    {
        $this->err = static function (string $text): void {
        };
        $this->application = new Application($this);
        $app->run = $this->run(...);
        $app->tree = $this->tree(...);
    }

    /**
     * Runs `$argv` (without the binary's name), writing stderr through `$err`.
     *
     * @param list<string> $argv
     * @param \Closure(string): void $err
     */
    public function run(array $argv, \Closure $err): Outcome
    {
        [$savedErr, $savedLast] = [$this->err, $this->last];
        [$this->err, $this->last] = [$err, null];
        try {
            return $this->dispatch($argv);
        } catch (Fail $fail) {
            return $this->failed($fail, in_array('--json', $argv, true), '');
        } finally {
            [$this->err, $this->last] = [$savedErr, $savedLast];
        }
    }

    /** @return array<string, mixed> `lingara __tree`'s projection of this runner's commands */
    public function tree(): array
    {
        return Tree::of($this->application);
    }

    /** @param list<string> $argv */
    private function dispatch(array $argv): Outcome
    {
        // The root `--version` is the framework's in the schema (16.10.26ab D2.10); its line is this ADR's (16.10.26af D1).
        if ($argv === ['--version']) {
            return new Outcome(ExitCode::Ok, 'lingara ' . Version::VERSION . " (php)\n");
        }
        $found = Resolver::command($this->application, $argv);
        if ($found === null) {
            return $this->topic($argv);
        }
        $buffer = new BufferedOutput();
        try {
            $this->application->doRun(new ArgvInput(['lingara', $found[0], ...$found[1]]), $buffer);
        } catch (ExceptionInterface $e) {
            throw Fail::usage(strtok($e->getMessage(), "\n") ?: 'the command line could not be parsed');
        }
        return $this->last ?? new Outcome(ExitCode::Ok, $buffer->fetch());
    }

    /**
     * A topic, or nothing: its help under `--help`, else `usage`, because a
     * topic alone does nothing (clap's and oclif's answer too).
     *
     * @param list<string> $argv
     */
    private function topic(array $argv): Outcome
    {
        $topic = Resolver::topic($argv);
        $help = TopicHelp::render($this->application, $topic);
        if (in_array('--help', $argv, true) || in_array('-h', $argv, true)) {
            return new Outcome(ExitCode::Ok, $help);
        }
        throw Fail::usage(($topic === '' ? 'lingara' : "lingara {$topic}") . ' needs a command; run it with --help to list them');
    }

    /** What symfony's `execute` hands over: validate, then run the row's handler. */
    public function execute(TableCommand $command, InputInterface $input): int
    {
        $in = new Input($input);
        $profile = $in->opt('profile') ?? $this->app->env->get('LINGARA_PROFILE');
        $settings = ConfigDir::profile($this->app->configDir, $profile ?? 'default');
        $out = new Output($this->err, $in->flag('json') || ($settings['output'] ?? null) === 'json');
        try {
            Validator::check($command->row, $in);
            // D3's order: LINGARA_API_URL, then platform.toml's (applied by the apps commands that read one), the profile's, endpoints.json's.
            $this->app->http->api = $this->app->env->get('LINGARA_API_URL') ?? $settings['api_url'] ?? $this->app->endpoints['api_url'];
            $handler = $command->row['run'];
            assert(is_array($handler) && is_callable($handler));
            $handler(new Ctx($this->app, $out, $profile ?? 'default', $profile !== null), $in);
            $this->last = new Outcome(ExitCode::Ok, $out->stdout);
        } catch (\Throwable $e) {
            $this->last = $this->failed(Fail::of($e), $out->json, $out->stdout);
        }
        return $this->last->exit->value;
    }

    private function failed(Fail $fail, bool $json, string $stdout): Outcome
    {
        foreach (Output::errorLines($fail, $json) as $line) {
            ($this->err)($line . "\n");
        }
        return new Outcome($fail->exit, $stdout, $fail);
    }
}
