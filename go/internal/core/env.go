package core

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

// Env is the process environment, read once, so tests pass their own.
type Env map[string]string

// ProcessEnv is the running process's environment.
func ProcessEnv() Env {
	env := Env{}
	for _, kv := range os.Environ() {
		if k, v, ok := strings.Cut(kv, "="); ok {
			env[k] = v
		}
	}
	return env
}

// Get is a set, non-empty variable.
func (e Env) Get(name string) (string, bool) {
	v, ok := e[name]
	return v, ok && v != ""
}

// FormatTime is seconds since the epoch as RFC 3339 in UTC,
// `YYYY-MM-DDTHH:MM:SSZ`: the one form the profile record's
// access_expires_at takes (16.10.26ad D4).
func FormatTime(secs int64) string {
	days, rem := floorDiv(secs, 86400)
	y, m, d := civil(days)
	return fmt.Sprintf("%04d-%02d-%02dT%02d:%02d:%02dZ", y, m, d, rem/3600, rem%3600/60, rem%60)
}

// ParseTime reads `YYYY-MM-DDTHH:MM:SS`, then `Z` or nothing; fractions are
// ignored. Days from the civil date, as the Rust kit does, so every kit
// reads the same stamps the same way.
func ParseTime(text string) (int64, bool) {
	if len(text) < 19 || text[4] != '-' || text[7] != '-' || (text[10] != 'T' && text[10] != 't') ||
		text[13] != ':' || text[16] != ':' {
		return 0, false
	}
	fields := [6][2]int{{0, 4}, {5, 7}, {8, 10}, {11, 13}, {14, 16}, {17, 19}}
	var n [6]int64
	for i, f := range fields {
		v, err := strconv.ParseInt(text[f[0]:f[1]], 10, 64)
		if err != nil {
			return 0, false
		}
		n[i] = v
	}
	return daysFromCivil(n[0], n[1], n[2])*86400 + n[3]*3600 + n[4]*60 + n[5], true
}

func floorDiv(a, b int64) (int64, int64) {
	q, r := a/b, a%b
	if r < 0 {
		q, r = q-1, r+b
	}
	return q, r
}

// daysFromCivil is Howard Hinnant's days_from_civil.
func daysFromCivil(y, m, d int64) int64 {
	if m <= 2 {
		y--
	}
	era, _ := floorDiv(y, 400)
	yoe := y - era*400
	shift := int64(9)
	if m > 2 {
		shift = -3
	}
	doy := (153*(m+shift)+2)/5 + d - 1
	doe := yoe*365 + yoe/4 - yoe/100 + doy
	return era*146097 + doe - 719468
}

func civil(days int64) (int64, int64, int64) {
	z := days + 719468
	era, _ := floorDiv(z, 146097)
	doe := z - era*146097
	yoe := (doe - doe/1460 + doe/36524 - doe/146096) / 365
	doy := doe - (365*yoe + yoe/4 - yoe/100)
	mp := (5*doy + 2) / 153
	d := doy - (153*mp+2)/5 + 1
	m := mp + 3
	if mp >= 10 {
		m = mp - 9
	}
	y := yoe + era*400
	if m <= 2 {
		y++
	}
	return y, m, d
}
