<?php

declare(strict_types=1);

namespace Lingara\Cli\Core;

/**
 * Contract B1's human table, rendered here rather than by symfony/console's
 * Table helper, which draws borders and wraps cells, both of which B1
 * forbids (ADR 16.10.26ad D6).
 */
final class TableRender
{
    /**
     * A header row and the rows under it: left-aligned, padded to the widest
     * cell, two spaces apart, no trailing whitespace, never truncated.
     *
     * @param list<string> $columns
     * @param list<list<string>> $rows
     */
    public static function render(array $columns, array $rows): string
    {
        $all = array_merge([$columns], $rows);
        $widths = [];
        foreach (array_keys($columns) as $i) {
            $widths[$i] = max(array_map(static fn (array $row): int => mb_strlen(self::cellText($row[$i] ?? '')), $all));
        }
        $out = '';
        foreach ($all as $row) {
            $cells = [];
            foreach ($widths as $i => $width) {
                $text = self::cellText($row[$i] ?? '');
                $cells[] = $text . str_repeat(' ', $width - mb_strlen($text));
            }
            $out .= rtrim(implode('  ', $cells)) . "\n";
        }
        return $out;
    }

    /** An empty value is `-`, so no cell is blank and the two-space split stays exact. */
    private static function cellText(string $cell): string
    {
        return $cell === '' ? '-' : $cell;
    }

    /**
     * One JSON value as a cell: a string as itself, a list of scalars joined
     * with commas, `null` and an empty list as `-`, anything else compact JSON.
     */
    public static function cell(mixed $value): string
    {
        if ($value === null || $value === []) {
            return '-';
        }
        if (is_string($value)) {
            return $value;
        }
        if (is_array($value) && self::scalars($value)) {
            return implode(',', array_map([self::class, 'cell'], $value));
        }
        return Json::encode($value);
    }

    /** @param array<mixed> $items */
    private static function scalars(array $items): bool
    {
        foreach ($items as $item) {
            if (is_array($item) || is_object($item)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A `get`: B1's two-column `FIELD  VALUE` form, one row per member in
     * the server's order.
     *
     * @return list<list<string>>
     */
    public static function fields(mixed $object): array
    {
        if (!$object instanceof \stdClass) {
            return [['value', self::cell($object)]];
        }
        $rows = [];
        foreach (get_object_vars($object) as $key => $value) {
            $rows[] = [(string) $key, self::cell($value)];
        }
        return $rows;
    }
}
