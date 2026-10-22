<?php

declare(strict_types=1);

namespace Lingara\Cli\Dev;

/**
 * Ctrl+C and SIGTERM (ADR 16.10.26af D9: "trap the signal, clean up, and
 * return `interrupted`"). With ext-pcntl loaded, `pcntl_async_signals` is on
 * and both signals reach one handler. Inside a `trap()` scope (`apps dev`'s
 * session, `login`'s wait) the handler only raises a flag, which the loop
 * polls, so it can end the session, stop its children and return 130. Outside
 * one, nothing is held open, so the handler ends the process with 130 at
 * once. Without ext-pcntl PHP cannot trap a signal at all: D1's one
 * exception, which `apps dev` refuses up front (`tool_missing_php_ext`).
 */
final class Signals
{
    private static bool $trapping = false;

    private static bool $stopped = false;

    /** @param \Closure(string): bool $extensionLoaded */
    public static function install(\Closure $extensionLoaded): void
    {
        if (!$extensionLoaded('pcntl') || !function_exists('pcntl_async_signals')) {
            return;
        }
        pcntl_async_signals(true);
        $handler = static function (): void {
            if (!self::$trapping) {
                exit(130);
            }
            self::$stopped = true;
        };
        pcntl_signal(SIGINT, $handler);
        pcntl_signal(SIGTERM, $handler);
    }

    /** From here until `release()`, a signal is a flag, not an exit. */
    public static function trap(): void
    {
        [self::$trapping, self::$stopped] = [true, false];
    }

    public static function release(): void
    {
        self::$trapping = false;
    }

    public static function stopped(): bool
    {
        return self::$stopped;
    }
}
