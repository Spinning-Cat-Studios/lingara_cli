<?php

declare(strict_types=1);

namespace Lingara\Cli\Store;

use Lingara\Cli\Core\Assets;
use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;

/**
 * The credential store a run uses (ADR 16.10.26ad D4, as ADR 16.10.26af D2
 * applies it to Ruby and PHP): **the file only**, because PHP has no
 * maintained cross-platform keyring. The file is the default, not a
 * fallback, so C3's fallback warning (which recommends
 * `LINGARA_KEYRING=keychain`) is never printed:
 *
 * - unset: the first write for a profile prints `store_file_only` once;
 * - `LINGARA_KEYRING=file`, which conformance sets: the file, silently;
 * - `LINGARA_KEYRING=keychain`: `auth_required` with `store_file_only`'s
 *   sentence, never a silent file write (C3's "keychain unavailable").
 */
final class Stores
{
    public const AUTO = 'auto';
    public const FILE = 'file';
    public const KEYCHAIN = 'keychain';

    public function __construct(public readonly string $mode, public readonly FileStore $file)
    {
    }

    public static function modeOf(?string $value): string
    {
        return in_array($value, [self::FILE, self::KEYCHAIN], true) ? $value : self::AUTO;
    }

    /** `store_file_only`, the one sentence both the notice and the refusal print. */
    public function fileOnly(): string
    {
        return Assets::message('store_file_only', ['path' => $this->file->path]);
    }

    private function refuseKeychain(): void
    {
        if ($this->mode === self::KEYCHAIN) {
            throw new Fail(ExitCode::AuthRequired, 'store_file_only', $this->fileOnly());
        }
    }

    /** @return array<string, string>|null */
    public function load(string $profile): ?array
    {
        $this->refuseKeychain();
        return $this->file->load($profile);
    }

    /**
     * Saves `$record`; the notice to print when this was the profile's first
     * write and nothing chose the file explicitly.
     *
     * @param array<string, string> $record
     */
    public function save(string $profile, array $record): ?string
    {
        $this->refuseKeychain();
        $first = !$this->file->has($profile);
        $this->file->save($profile, $record);
        return $first && $this->mode === self::AUTO ? $this->fileOnly() : null;
    }

    public function delete(string $profile): void
    {
        $this->refuseKeychain();
        $this->file->delete($profile);
    }
}
