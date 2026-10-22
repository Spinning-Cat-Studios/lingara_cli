<?php

declare(strict_types=1);

namespace Lingara\Cli\Dev;

use Lingara\Cli\Core\Assets;
use Lingara\Cli\Core\Env;
use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;
use Symfony\Component\Process\Process;

/**
 * cloudflared's lookup (ADR 16.10.26ae D8): `LINGARA_CLOUDFLARED` and then
 * only that path, else `cloudflared` on `PATH`, refused below `tools.toml`'s
 * minimum with `tool_missing_cloudflared`, offline (D7 step 2).
 */
final class Cloudflared
{
    public static function missing(): Fail
    {
        return Fail::message(ExitCode::ToolMissing, 'tool_missing_cloudflared');
    }

    private static function onPath(Env $env): ?string
    {
        $exe = PHP_OS_FAMILY === 'Windows' ? 'cloudflared.exe' : 'cloudflared';
        foreach (explode(PATH_SEPARATOR, $env->get('PATH') ?? '') as $dir) {
            if ($dir !== '' && is_file("{$dir}/{$exe}")) {
                return "{$dir}/{$exe}";
            }
        }
        return null;
    }

    /** @return list<int>|null the first `a.b.c` on `cloudflared --version`'s first line */
    private static function versionOf(string $binary): ?array
    {
        try {
            $process = new Process([$binary, '--version'], null, null, null, 10);
            $process->run();
        } catch (\Throwable) {
            return null;
        }
        $first = explode("\n", $process->getOutput())[0];
        foreach (preg_split('/\s+/', $first) ?: [] as $word) {
            if (preg_match('/^\d+(\.\d+)+$/', $word) === 1) {
                return array_map('intval', explode('.', $word));
            }
        }
        return null;
    }

    /**
     * @param list<int> $version
     * @param list<int> $min
     */
    private static function atLeast(array $version, array $min): bool
    {
        for ($i = 0; $i < max(count($version), count($min)); $i++) {
            if (($version[$i] ?? 0) !== ($min[$i] ?? 0)) {
                return ($version[$i] ?? 0) > ($min[$i] ?? 0);
            }
        }
        return true;
    }

    public static function find(Env $env): string
    {
        $explicit = $env->get('LINGARA_CLOUDFLARED');
        $binary = $explicit !== null ? (is_file($explicit) ? $explicit : null) : self::onPath($env);
        if ($binary === null) {
            throw self::missing();
        }
        $version = self::versionOf($binary);
        return $version !== null && self::atLeast($version, Assets::cloudflaredMinVersion()) ? $binary : throw self::missing();
    }

    /** The `https://…trycloudflare.com` hostname on one log line, if any. */
    public static function hostnameIn(string $line): ?string
    {
        return preg_match('#https://([A-Za-z0-9.-]+)#', $line, $m) === 1 && str_ends_with($m[1], '.trycloudflare.com') ? $m[1] : null;
    }
}
