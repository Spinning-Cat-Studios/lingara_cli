<?php

declare(strict_types=1);

namespace Lingara\Cli\Core;

/**
 * Why a command stopped: its exit, the code and text contract B2 prints,
 * any extra lines of advice, and what a part-way create already made
 * (ADR 16.10.26ad D2). Every command throws this one type; the runner turns
 * it into B2's stderr line, so no command prints its own refusal.
 */
final class Fail extends \RuntimeException
{
    /** @var list<string> */
    public array $hints = [];

    /** @var list<array{kind: string, id: string}> */
    public array $made = [];

    /** The refusing answer's body, for a command that re-words it (apps dev's `dev_session_held`). */
    public ?string $body = null;

    public function __construct(public readonly ExitCode $exit, public readonly string $errorCode, string $message)
    {
        parent::__construct($message);
    }

    /** One more line of advice, once. */
    public function hint(string $line): self
    {
        if (!in_array($line, $this->hints, true)) {
            $this->hints[] = $line;
        }
        return $this;
    }

    public static function usage(string $message): self
    {
        return new self(ExitCode::Usage, 'usage', $message);
    }

    public static function auth(string $message): self
    {
        return new self(ExitCode::AuthRequired, 'auth_required', $message);
    }

    public static function failure(string $message): self
    {
        return new self(ExitCode::Failure, 'failure', $message);
    }

    /**
     * A refusal with a `messages.en.json` text: code and message id are the
     * same word, as every 16.10.26ae row's are.
     *
     * @param array<string, string> $vars
     */
    public static function message(ExitCode $exit, string $id, array $vars = []): self
    {
        return new self($exit, $id, Assets::message($id, $vars));
    }

    /** Anything thrown, as a refusal: a `Fail` stays itself, anything else is `failure`. */
    public static function of(\Throwable $e): self
    {
        return $e instanceof self ? $e : self::failure($e->getMessage());
    }
}
