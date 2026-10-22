<?php

declare(strict_types=1);

namespace Lingara\Cli\Core;

/**
 * The exit codes (16.10.26ab D3, rows 0–8 and 130; 16.10.26ae D10, rows
 * 9–18). A case is named, never numbered, by the conformance suite, so each
 * row carries its schema name too, which `apps dev --json` reports.
 */
enum ExitCode: int
{
    case Ok = 0;
    case Failure = 1;
    case Usage = 2;
    case AuthRequired = 3;
    case NotFound = 4;
    case Refused = 5;
    case NotConfirmed = 6;
    case Unavailable = 7;
    case PlatformDisabled = 8;
    case ToolMissing = 9;
    case TunnelFailed = 10;
    case DevSessionHeld = 11;
    case DevSessionLost = 12;
    case AppExited = 13;
    case ConnectionScripted = 14;
    case RenderUrlEphemeral = 15;
    case PlatformTomlSecret = 16;
    case PlatformTomlSchema = 17;
    case AppNotListening = 18;
    case Interrupted = 130;

    /** The row's schema name: `NotFound` is `not_found`. */
    public function label(): string
    {
        return strtolower((string) preg_replace('/(?<!^)[A-Z]/', '_$0', $this->name));
    }
}
