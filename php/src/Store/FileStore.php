<?php

declare(strict_types=1);

namespace Lingara\Cli\Store;

use Lingara\Cli\Core\Fail;

/**
 * `<config dir>/credentials.json`, mode `0600` in a `0700` directory, shaped
 * `{"schema": 1, "profiles": {"<profile>": <record>}}` (ADR 16.10.26ad D4,
 * as `rust/src/store/file.rs` writes it): profiles in byte order, each record
 * in the contract's field order, two-space indentation, a final newline. In
 * this kit it is the only store (ADR 16.10.26af D2).
 */
final class FileStore
{
    /** The record's fields, in the order every kit writes them. */
    public const FIELDS = ['developer_id', 'email', 'scopes', 'refresh_token', 'access_token', 'access_expires_at'];

    public readonly string $path;

    public function __construct(string $dir)
    {
        $this->path = $dir . DIRECTORY_SEPARATOR . 'credentials.json';
    }

    /** @return array<string, array<string, string>> */
    private function read(): array
    {
        if (!file_exists($this->path)) {
            return [];
        }
        $file = json_decode((string) @file_get_contents($this->path), true);
        if (!is_array($file)) {
            throw Fail::failure("the credential store could not be read: {$this->path} is not JSON");
        }
        $profiles = is_array($file['profiles'] ?? null) ? $file['profiles'] : [];
        return array_map(static fn (mixed $r): array => self::record(is_array($r) ? $r : []), $profiles);
    }

    /**
     * @param array<mixed> $raw
     * @return array<string, string> D4's record, every field a string
     */
    public static function record(array $raw): array
    {
        $out = [];
        foreach (self::FIELDS as $field) {
            $out[$field] = is_string($raw[$field] ?? null) ? $raw[$field] : '';
        }
        return $out;
    }

    /** @param array<string, array<string, string>> $profiles */
    private function write(array $profiles): void
    {
        $dir = dirname($this->path);
        if (!is_dir($dir) && !@mkdir($dir, 0700, true) && !is_dir($dir)) {
            throw Fail::failure("the credential store could not be written: cannot create {$dir}");
        }
        @chmod($dir, 0700);
        ksort($profiles, SORT_STRING);
        $file = ['schema' => 1, 'profiles' => $profiles === [] ? new \stdClass() : $profiles];
        $text = (string) json_encode($file, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
        // PHP indents by four; the contract's file (serde's pretty printer) by two.
        $text = (string) preg_replace_callback('/^( +)/m', static fn (array $m): string => str_repeat(' ', intdiv(strlen($m[1]), 2)), $text);
        if (@file_put_contents($this->path, $text . "\n") === false) {
            throw Fail::failure("the credential store could not be written: {$this->path}");
        }
        @chmod($this->path, 0600);
    }

    public function has(string $profile): bool
    {
        try {
            return isset($this->read()[$profile]);
        } catch (Fail) {
            return false;
        }
    }

    /** @return array<string, string>|null */
    public function load(string $profile): ?array
    {
        return $this->read()[$profile] ?? null;
    }

    /** @param array<string, string> $record */
    public function save(string $profile, array $record): void
    {
        $profiles = $this->read();
        $profiles[$profile] = self::record($record);
        $this->write($profiles);
    }

    /** Removes the profile; the file goes when it held only that one. */
    public function delete(string $profile): void
    {
        $profiles = $this->read();
        if (!isset($profiles[$profile])) {
            return;
        }
        unset($profiles[$profile]);
        if ($profiles === []) {
            @unlink($this->path);
            return;
        }
        $this->write($profiles);
    }
}
