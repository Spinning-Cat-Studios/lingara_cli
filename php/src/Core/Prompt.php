<?php

declare(strict_types=1);

namespace Lingara\Cli\Core;

/** What the y/n question names: the object, its cascade, its pins (ADR 16.10.26ad D5). */
final class Prompt
{
    public function __construct(
        public readonly string $kind,
        public readonly string $name,
        public readonly string $id,
        /** The kind's `consequence_*` line, or `consequence_version`. */
        public readonly string $consequence,
        public readonly int $pinnedBy = 0,
    ) {
    }

    /** `confirm_yes_no`, the consequence, and `confirm_pinned_by` when pinned. */
    public function question(): string
    {
        $lines = [Assets::message('confirm_yes_no', ['kind' => $this->kind, 'name' => $this->name, 'id' => $this->id]), $this->consequence];
        if ($this->pinnedBy > 0) {
            $lines[] = Assets::message('confirm_pinned_by', ['count' => (string) $this->pinnedBy]);
        }
        return implode("\n", $lines);
    }
}
