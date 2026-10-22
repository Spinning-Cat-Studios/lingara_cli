<?php

declare(strict_types=1);

namespace Lingara\Cli\Core;

/**
 * Contract B1's streams: data to stdout, everything else to stderr, and
 * under `--json` exactly one JSON value on stdout (ADR 16.10.26ad D6).
 * stdout is buffered and written once, by the entry script, so an
 * in-process verb that canvas apply runs (D7 step 4) hands its JSON back
 * instead of printing it.
 */
final class Output
{
    public string $stdout = '';

    /** @param \Closure(string): void $err */
    public function __construct(private readonly \Closure $err, public bool $json)
    {
    }

    /** Text for stdout, as is. */
    public function print(string $text): void
    {
        $this->stdout .= $text;
    }

    /** One line on stderr. */
    public function note(string $line): void
    {
        ($this->err)($line . "\n");
    }

    /** A prompt on stderr, left on the line it ends. */
    public function prompt(string $text): void
    {
        ($this->err)($text);
    }

    public function jsonValue(mixed $value): void
    {
        $this->print(Json::encode($value) . "\n");
    }

    /** One server value: as JSON, or as B1's `FIELD  VALUE` table. */
    public function value(mixed $value): void
    {
        if ($this->json) {
            $this->jsonValue($value);
            return;
        }
        $this->print(TableRender::render(['FIELD', 'VALUE'], TableRender::fields($value)));
    }

    /**
     * A list: one JSON array, or the schema's columns read from each item.
     *
     * @param list<mixed> $items
     * @param list<array{string, string}> $columns heading and the member it reads
     */
    public function list(array $items, array $columns): void
    {
        if ($this->json) {
            $this->jsonValue($items);
            return;
        }
        $rows = array_map(
            static fn (mixed $item): array => array_map(static fn (array $c): string => TableRender::cell(Json::get($item, $c[1])), $columns),
            $items,
        );
        $this->print(TableRender::render(array_column($columns, 0), $rows));
    }

    /** A command with nothing to print: nothing, or `{}` under `--json`. */
    public function nothing(): void
    {
        if ($this->json) {
            $this->print("{}\n");
        }
    }

    /**
     * Contract B2's refusal on stderr: `error: <text> (<code>)` and any
     * advice lines, or under `--json` one JSON line. What a part-way create
     * made is named either way (D2).
     *
     * @return list<string>
     */
    public static function errorLines(Fail $fail, bool $json): array
    {
        if ($json) {
            $error = ['code' => $fail->errorCode, 'message' => $fail->getMessage(), 'exit' => $fail->exit->value];
            if ($fail->hints !== []) {
                $error['hints'] = $fail->hints;
            }
            if ($fail->made !== []) {
                $error['made'] = $fail->made;
            }
            return [Json::encode(['error' => $error])];
        }
        $lines = ["error: {$fail->getMessage()} ({$fail->errorCode})", ...$fail->hints];
        foreach ($fail->made as $made) {
            $lines[] = "made: {$made['kind']} {$made['id']}";
        }
        return $lines;
    }
}
