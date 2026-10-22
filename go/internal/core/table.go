package core

import (
	"strings"
	"unicode/utf8"

	"getlingara.com/cli/internal/jsonv"
)

// Contract B1's human table, rendered here rather than by a table package,
// because those draw borders and wrap cells, which B1 forbids (16.10.26ad
// D6).

// Render is a header row and the rows under it: left-aligned, padded to the
// widest cell, two spaces apart, no trailing whitespace, never truncated.
func Render(columns []string, rows [][]string) string {
	all := append([][]string{columns}, rows...)
	widths := make([]int, len(columns))
	for i := range columns {
		for _, row := range all {
			widths[i] = max(widths[i], utf8.RuneCountInString(cellText(at(row, i))))
		}
	}
	var out strings.Builder
	for _, row := range all {
		cells := make([]string, len(columns))
		for i := range columns {
			text := cellText(at(row, i))
			cells[i] = text + strings.Repeat(" ", widths[i]-utf8.RuneCountInString(text))
		}
		out.WriteString(strings.TrimRight(strings.Join(cells, "  "), " "))
		out.WriteByte('\n')
	}
	return out.String()
}

func at(row []string, i int) string {
	if i < len(row) {
		return row[i]
	}
	return ""
}

// cellText makes an empty value `-`, so no cell is blank and the split
// stays exact.
func cellText(cell string) string {
	if cell == "" {
		return "-"
	}
	return cell
}

// Cell is one JSON value as a cell: a string as itself, a list of scalars
// joined with commas, null and an empty list as `-`, anything else compact
// JSON.
func Cell(v any) string {
	switch t := v.(type) {
	case nil:
		return "-"
	case string:
		return t
	case []any:
		return listCell(t)
	default:
		return jsonv.Marshal(v)
	}
}

func listCell(items []any) string {
	if len(items) == 0 {
		return "-"
	}
	parts := make([]string, len(items))
	for i, item := range items {
		if jsonv.IsObject(item) || jsonv.IsArray(item) {
			return jsonv.Marshal(items)
		}
		parts[i] = Cell(item)
	}
	return strings.Join(parts, ",")
}

// Fields is a get: B1's two-column FIELD VALUE form, one row per member.
func Fields(v any) [][]string {
	o, ok := v.(*jsonv.Object)
	if !ok {
		return [][]string{{"value", Cell(v)}}
	}
	rows := make([][]string, 0, o.Len())
	for _, k := range o.Keys() {
		rows = append(rows, []string{k, Cell(o.Get(k))})
	}
	return rows
}
