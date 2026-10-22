<?php

declare(strict_types=1);

namespace Lingara\Cli\Table;

use Lingara\Cli\Handlers\AppsDevHandler;
use Lingara\Cli\Handlers\AppsHandlers;

/** `apps …` (ADR 16.10.26ad D1; 16.10.26ae D1, D6, D7, D9). */
final class AppsTable
{
    private const PORT = ['name' => 'port', 'type' => 'int', 'value' => 'n'];
    private const OPTIONAL_NAME = [['name' => 'name', 'type' => 'string']];
    private const SEMVER = [['name' => 'id', 'type' => 'string', 'required' => true], ['name' => 'semver', 'type' => 'string', 'required' => true]];
    private const LANGS = ['typescript', 'rust', 'go', 'java', 'kotlin', 'ruby', 'php'];

    public const COMMANDS = [
        'apps create' => [
            'help' => 'Scaffold an app project and register it; or, with --client and --manifest, register an app from a manifest file.',
            'run' => [AppsHandlers::class, 'create'],
            'args' => [['name' => 'name', 'type' => 'string', 'required' => true]],
            'flags' => [
                ['name' => 'client', 'type' => 'string', 'value' => 'id', 'help' => "The OAuth client's id (with --manifest)"],
                ['name' => 'manifest', 'type' => 'path', 'value' => 'path', 'help' => "The app's manifest file (with --client)"],
                ['name' => 'lang', 'type' => 'enum', 'values' => self::LANGS, 'value' => 'app-kit-lang', 'help' => "The app's language"],
                ['name' => 'bare', 'type' => 'bool', 'help' => "The manifest as code in the app's language, and a handler on the kit's primitives"],
                ['name' => 'dir', 'type' => 'path', 'value' => 'path', 'help' => 'Where to write the project; default ./<name>'],
                ['name' => 'no-register', 'type' => 'bool', 'help' => 'Scaffold only; register later with apps update'],
                ['name' => 'description', 'type' => 'string', 'value' => 'text', 'help' => 'One sentence about the app'],
                [...self::PORT, 'help' => 'The port the app listens on; default 8787'],
                ['name' => 'slot', 'type' => 'enum', 'values' => ['home.side', 'plans.empty_detail'], 'repeatable' => true, 'value' => 'slot', 'help' => "Where the app's card may appear; default home.side"],
                ['name' => 'context', 'type' => 'string', 'repeatable' => true, 'value' => 'slice', 'help' => 'A context slice the app receives'],
                Flags::CONFIG,
            ],
            'conflicts' => [
                ['manifest', 'lang'], ['manifest', 'bare'], ['manifest', 'dir'], ['manifest', 'no-register'], ['manifest', 'description'],
                ['manifest', 'port'], ['manifest', 'slot'], ['manifest', 'context'], ['client', 'lang'], ['client', 'bare'], ['client', 'dir'],
            ],
        ],
        'apps list' => [
            'help' => 'List your apps, or the apps platform.toml pins.',
            'run' => [AppsHandlers::class, 'list'],
            'flags' => [['name' => 'pinned', 'type' => 'bool', 'help' => "List platform.toml's rows instead, reading nothing from the platform"], Flags::CONFIG],
        ],
        'apps get' => ['help' => 'Show one app.', 'run' => [AppsHandlers::class, 'get'], 'args' => Flags::ID],
        'apps update' => [
            'help' => "Without --manifest, push the project's manifest to its dev version (--version cuts a draft); with an id and --manifest, make a new version from a file.",
            'run' => [AppsHandlers::class, 'update'],
            'args' => [['name' => 'id', 'type' => 'string']],
            'flags' => [
                ['name' => 'manifest', 'type' => 'path', 'value' => 'path', 'help' => "The app's manifest file: makes a new version"],
                ['name' => 'version', 'type' => 'string', 'value' => 'semver', 'help' => "Cut a draft version from the project's manifest"],
                Flags::CONFIG,
            ],
            'conflicts' => [['manifest', 'version']],
        ],
        'apps dev' => [
            'help' => 'Run the app behind a tunnel and point your private dev version at it, until Ctrl+C.',
            'run' => [AppsDevHandler::class, 'dev'],
            'args' => self::OPTIONAL_NAME,
            'flags' => [
                [...self::PORT, 'help' => "The port the app listens on; default the row's"],
                ['name' => 'tunnel', 'type' => 'enum', 'values' => ['cloudflared', 'none'], 'help' => 'The tunnel to open; none needs --render-url'],
                ['name' => 'render-url', 'type' => 'string', 'value' => 'https-url', 'help' => 'With --tunnel none, the URL the dev version renders from'],
                ['name' => 'take-over', 'type' => 'bool', 'help' => "End another terminal's session on this app first"],
                ['name' => 'write-env', 'type' => 'path', 'value' => 'file', 'help' => "Also write the session's variables to this gitignored file, mode 0600"],
                ['name' => 'show-env', 'type' => 'bool', 'help' => 'List the names of the variables the app receives'],
                ['name' => 'reset', 'type' => 'bool', 'help' => 'Return your install to the serving version, and do nothing else'],
                Flags::CONFIG,
            ],
            'conflicts' => [['reset', 'port'], ['reset', 'tunnel'], ['reset', 'render-url'], ['reset', 'take-over'], ['reset', 'write-env'], ['reset', 'show-env']],
        ],
        'apps status' => [
            'help' => "Show where the app's dev version renders from: live, parked, or the serving version.",
            'run' => [AppsHandlers::class, 'status'],
            'args' => self::OPTIONAL_NAME,
            'flags' => [Flags::CONFIG],
        ],
        'apps pin' => [
            'help' => 'Add an app to platform.toml (and its example), by id or by name.',
            'run' => [AppsHandlers::class, 'pin'],
            'args' => [['name' => 'app', 'type' => 'string', 'required' => true]],
            'flags' => [['name' => 'path', 'type' => 'path', 'value' => 'dir', 'help' => 'The project directory; needed for a name'], Flags::CONFIG],
        ],
        'apps unpin' => [
            'help' => "Remove an app's row from platform.toml; the example changes by review.",
            'run' => [AppsHandlers::class, 'unpin'],
            'args' => [['name' => 'name', 'type' => 'string', 'required' => true]],
            'flags' => [Flags::CONFIG],
        ],
        'apps delete' => ['help' => 'Delete an app, or one of its versions.', 'run' => [AppsHandlers::class, 'delete'], 'args' => Flags::ID, 'flags' => Flags::DELETE],
        'apps submit' => [
            'help' => 'Submit a version of an app for review, to make it public.',
            'run' => [AppsHandlers::class, 'submit'],
            'args' => Flags::ID,
            'flags' => [['name' => 'version', 'type' => 'string', 'required' => true, 'value' => 'semver', 'help' => 'The version to submit']],
        ],
        'apps versions list' => ['help' => "List an app's versions.", 'run' => [AppsHandlers::class, 'versionsList'], 'args' => Flags::ID],
        'apps versions publish' => ['help' => "Publish an approved version, or a private app's draft.", 'run' => [AppsHandlers::class, 'versionsPublish'], 'args' => self::SEMVER],
        'apps versions withdraw' => ['help' => 'Withdraw a version.', 'run' => [AppsHandlers::class, 'versionsWithdraw'], 'args' => self::SEMVER],
    ];
}
