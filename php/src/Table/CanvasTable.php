<?php

declare(strict_types=1);

namespace Lingara\Cli\Table;

use Lingara\Cli\Handlers\CanvasHandlers;

/** `canvas …` (ADR 16.10.26ad D1, D7): content through `/v1/canvases`, lifecycle through `/platform/objects`. */
final class CanvasTable
{
    public const COMMANDS = [
        'canvas create' => [
            'help' => 'Create a canvas, from a definition file or empty.',
            'run' => [CanvasHandlers::class, 'create'],
            'flags' => [
                ['name' => 'name', 'type' => 'string', 'help' => "The canvas's name"],
                ['name' => 'file', 'type' => 'path', 'value' => 'definition.json', 'help' => 'The definition; absent, an empty one'],
            ],
        ],
        'canvas list' => ['help' => 'List your canvases.', 'run' => [CanvasHandlers::class, 'list']],
        'canvas get' => ['help' => 'Show one canvas.', 'run' => [CanvasHandlers::class, 'get'], 'args' => Flags::ID],
        'canvas update' => [
            'help' => 'Rename a canvas.',
            'run' => [CanvasHandlers::class, 'update'],
            'args' => Flags::ID,
            'flags' => [['name' => 'name', 'type' => 'string', 'required' => true, 'help' => 'The new name']],
        ],
        'canvas draft' => [
            'help' => "Save a definition as the canvas's draft. Creates nothing else.",
            'run' => [CanvasHandlers::class, 'draft'],
            'args' => Flags::ID,
            'flags' => [['name' => 'file', 'type' => 'path', 'required' => true, 'value' => 'definition.json', 'help' => 'The definition']],
        ],
        'canvas apply' => [
            'help' => "Create the canvas's objects by running this CLI's own commands, then activate it.",
            'run' => [CanvasHandlers::class, 'apply'],
            'args' => Flags::ID,
            'flags' => [
                ['name' => 'dry-run', 'type' => 'bool', 'help' => 'Print what each object would do, and write nothing'],
                ['name' => 'print-commands', 'type' => 'bool', 'help' => 'Print the commands apply would run, and write nothing'],
            ],
            'conflicts' => [['dry-run', 'print-commands']],
        ],
        'canvas delete' => [
            'help' => 'Delete a canvas, or one version. The objects it made stay.',
            'run' => [CanvasHandlers::class, 'delete'],
            'args' => Flags::ID,
            'flags' => Flags::DELETE,
        ],
    ];
}
