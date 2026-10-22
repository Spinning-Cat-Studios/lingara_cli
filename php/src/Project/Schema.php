<?php

declare(strict_types=1);

namespace Lingara\Cli\Project;

use Lingara\Cli\Core\Assets;
use Opis\JsonSchema\Errors\ErrorFormatter;
use Opis\JsonSchema\Validator;

/**
 * `spec/platform-toml.schema.json`'s rules over the parsed file (ADR
 * 16.10.26ae D4), through opis/json-schema (ADR 16.10.26af D2) and the
 * kit's committed copy of the schema, so a file one kit refuses every kit
 * refuses. The TOML parser's own types go in as JSON would see them: a
 * datetime is an object, so it is never a string the schema accepts.
 */
final class Schema
{
    /** A lowercase letter, then up to 39 lowercase letters, digits or hyphens. */
    public static function appNameOk(string $name): bool
    {
        return preg_match('/^[a-z][a-z0-9-]{0,39}$/', $name) === 1;
    }

    /** The first rule the file breaks, as a reason, or null. */
    public static function check(mixed $value): ?string
    {
        $data = json_decode((string) json_encode($value));
        $schema = json_decode(Assets::read('platform-toml.schema.json'));
        $error = (new Validator())->validate($data, is_object($schema) ? $schema : true)->error();
        if ($error === null) {
            return null;
        }
        $errors = (new ErrorFormatter())->format($error, false);
        $at = (string) array_key_first($errors);
        $message = $errors[$at] ?? $error->message();
        return ($at === '' || $at === '/' ? '' : "{$at}: ") . (is_string($message) ? $message : 'does not match the schema');
    }
}
