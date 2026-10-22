<?php

declare(strict_types=1);

namespace Lingara\Cli\Project;

use Lingara\Cli\Core\Assets;
use Lingara\Cli\Core\Ctx;
use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;
use Lingara\Cli\Core\Json;
use Lingara\Cli\Literal\ConnectionLiteral;
use Lingara\Cli\Literal\Refusal;
use Lingara\Cli\Platform\Creation;
use Lingara\Cli\Platform\Kind;
use Symfony\Component\Process\Process;

/**
 * A project's manifest and its registration (ADR 16.10.26ae D1 step 2, D6
 * steps 1, 4 and 5): read from `lingara/connection.js` by the literal
 * reader, or from `manifest_command`'s stdout; checked; its warnings
 * printed; and, for a row with no id, registered as a private app.
 *
 * @phpstan-type Toml array{file: string, api: ?string, profile: ?string, apps: list<array<string, mixed>>}
 */
final class ProjectManifest
{
    /** @return list<string> the argv that runs `$line` through the platform shell (`sh -c`, `cmd /C`) */
    public static function shell(string $line): array
    {
        return PHP_OS_FAMILY === 'Windows' ? ['cmd', '/C', $line] : ['sh', '-c', $line];
    }

    /**
     * The manifest's source as messages name it, relative to `$cwd`.
     *
     * @param array{file: string, api: ?string, profile: ?string, apps: list<array<string, mixed>>} $toml
     * @param array<string, mixed> $row
     */
    public static function source(array $toml, array $row, string $cwd): string
    {
        if (is_string($row['manifest_command'])) {
            return $row['manifest_command'];
        }
        $file = PlatformToml::join(PlatformToml::projectDir($toml['file'], $row), is_string($row['manifest']) ? $row['manifest'] : 'lingara/connection.js');
        return PlatformToml::shown($file, $cwd);
    }

    /** D3: `manifest_command` through the platform shell, in the project; its stdout is the wire manifest. */
    private static function runCommand(Ctx $ctx, string $command, string $dir): mixed
    {
        $process = new Process(self::shell($command), $dir, null, null, null);
        $status = $process->run(static function (string $type, string $buffer) use ($ctx): void {
            if ($type === Process::ERR) {
                $ctx->out->prompt($buffer);
            }
        });
        if ($status !== 0) {
            throw Fail::failure("{$command} exited {$status}; fix it, then run lingara apps update");
        }
        $out = $process->getOutput();
        return Json::isJson($out) ? Json::decode($out) : throw Fail::usage("{$command} printed no manifest JSON: " . json_last_error_msg());
    }

    /**
     * @param array{file: string, api: ?string, profile: ?string, apps: list<array<string, mixed>>} $toml
     * @param array<string, mixed> $row
     */
    private static function fromConnection(array $toml, array $row, string $name): \stdClass
    {
        $file = PlatformToml::join(PlatformToml::projectDir($toml['file'], $row), is_string($row['manifest']) ? $row['manifest'] : 'lingara/connection.js');
        $text = is_file($file) ? @file_get_contents($file) : false;
        if ($text === false) {
            throw Fail::usage("{$name}: the file cannot be read");
        }
        try {
            return ConnectionLiteral::appManifest($text);
        } catch (Refusal $r) {
            throw ConnectionLiteral::fail($r, $name);
        }
    }

    /**
     * The wire manifest the project describes, checked before any request.
     *
     * @param array{file: string, api: ?string, profile: ?string, apps: list<array<string, mixed>>} $toml
     * @param array<string, mixed> $row
     */
    public static function read(Ctx $ctx, array $toml, array $row, string $cwd): mixed
    {
        $name = self::source($toml, $row, $cwd);
        $manifest = is_string($row['manifest_command'])
            ? self::runCommand($ctx, $row['manifest_command'], PlatformToml::projectDir($toml['file'], $row))
            : self::fromConnection($toml, $row, $name);
        $rule = ManifestRules::brokenRule($manifest);
        if ($rule !== null) {
            throw new Fail(ExitCode::Usage, 'manifest_invalid', "{$name}: the manifest breaks the {$rule} rule");
        }
        return $manifest;
    }

    /** @return list<string> D6 step 5: one `manifest_warning` line each, never a failure */
    public static function warn(Ctx $ctx, mixed $manifest): array
    {
        $codes = ManifestRules::warnings($manifest);
        foreach ($codes as $code) {
            $ctx->out->note(Assets::message('manifest_warning', ['code' => $code, 'text' => ManifestRules::warningText($code)]));
        }
        return $codes;
    }

    /** The display name D2 derives: each `-` a space, each word capitalised. */
    public static function displayName(string $name): string
    {
        return implode(' ', array_map('ucfirst', explode('-', $name)));
    }

    /**
     * D1 step 2 / D6 step 4: the private OAuth client, then the app under
     * it through the registration form's own steps, the id written into the
     * row. Prints the app as the registration form does.
     *
     * @param array{file: string, api: ?string, profile: ?string, apps: list<array<string, mixed>>} $toml
     * @param array<string, mixed> $row
     */
    public static function register(Ctx $ctx, array $toml, array $row, mixed $manifest): mixed
    {
        $title = self::displayName((string) $row['name']);
        $clientBody = ['client_type' => 'confidential', 'allowed_scopes' => [], 'billing_mode' => 'allowance', 'redirect_uris' => []];
        $made = new Creation();
        $app = $made->run($ctx, static function () use ($ctx, $made, $title, $clientBody, $manifest): array {
            $client = $made->object($ctx, Kind::oauthClient(), $title, null, $clientBody);
            $created = $made->object($ctx, Kind::app(), $title, Json::str($client, 'id'), ['manifest' => $manifest]);
            return [Kind::app(), Json::str($created, 'id')];
        });
        Write::setId($toml['file'], (string) $row['name'], Json::str($app, 'id'));
        return $app;
    }
}
