<?php

declare(strict_types=1);

namespace Lingara\Cli\Core;

/**
 * The delete that asks twice (ADR 16.10.26ad D5; contract B3, invariant I1).
 * One function, called by every dangerous command, so no command can carry
 * a weaker copy.
 *
 * - `-y --confirm <email>` replaces both prompts, interactive or not, and
 *   sends no read for a prompt; the email is checked before anything is
 *   destroyed. Neither flag alone is confirmation.
 * - Otherwise a non-interactive run is refused **before any request**, the
 *   read and any token exchange included.
 * - Interactive: the command's read fills the prompt, `y`/`yes` continues,
 *   then the typed email must match. There is no retry loop.
 */
final class Confirm
{
    /** Trimmed and ASCII-lowercased: the comparison's rule and the form the request carries. */
    public static function canonical(string $email): string
    {
        return strtolower(trim($email));
    }

    /** `dev@example.com` is `d…@example.com`; a one-character local part is `…@example.com`. */
    public static function hint(string $email): string
    {
        $at = strpos($email, '@');
        [$local, $domain] = $at === false ? [$email, ''] : [substr($email, 0, $at), substr($email, $at + 1)];
        $first = mb_strlen($local) > 1 ? mb_substr($local, 0, 1) : '';
        return "{$first}…@{$domain}";
    }

    private static function notConfirmed(string $id): Fail
    {
        return Fail::message(ExitCode::NotConfirmed, $id);
    }

    /**
     * The ritual; the canonical email the destructive request then carries.
     *
     * @param array{yes: bool, confirm: ?string} $flags
     * @param \Closure(): Prompt $read the command's read, for the prompt
     */
    public static function destructive(Ctx $ctx, array $flags, \Closure $read): string
    {
        if ($flags['yes'] && $flags['confirm'] !== null) {
            $email = self::canonical($ctx->email());
            return self::canonical($flags['confirm']) === $email ? $email : throw self::notConfirmed('confirm_email_mismatch');
        }
        if (!$ctx->app->terminal->interactive()) {
            throw self::notConfirmed('confirm_non_tty_refused');
        }
        $ctx->out->prompt($read()->question() . ' [y/N] ');
        $answer = strtolower(trim($ctx->app->terminal->readLine() ?? ''));
        if ($answer !== 'y' && $answer !== 'yes') {
            throw self::notConfirmed('confirm_declined');
        }
        $email = self::canonical($ctx->email());
        $ctx->out->prompt(Assets::message('confirm_type_email', ['hint' => self::hint($email)]) . ' ');
        $typed = self::canonical($ctx->app->terminal->readLine() ?? '');
        return $typed === $email ? $email : throw self::notConfirmed('confirm_email_mismatch');
    }
}
