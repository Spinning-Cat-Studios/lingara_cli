package core

// The one response classifier (ADR 16.10.26ad D8; contract B2). Its rules
// are data: an ordered list of (predicate, verdict) entries, the first
// match winning and the last the catch-all. 16.10.26ae D10's rule 4a is
// one more entry, after rule 4.

type rule struct {
	matches func(Answer) bool
	verdict func(Answer) Verdict
}

func exitVerdict(exit Exit) func(Answer) Verdict {
	return func(Answer) Verdict { return Verdict{Exit: exit} }
}

func platformDisabled(a Answer) bool {
	code, _ := a.member("code")
	dark := a.Status == 404 && a.PlatformRoute && code == PlatformDisabledCode
	door := a.Status == 403 && code == PlatformAccessCode
	lingara, _ := a.member("lingara_code")
	token := a.TokenRoute && lingara == PlatformAccessCode
	return dark || door || token
}

// codedNotFound is rule 4, whose one exception is keyed on route and code.
func codedNotFound(a Answer) Verdict {
	code, _ := a.member("code")
	if a.Route == "developer.me" && code == "developer_not_found" {
		return Verdict{Exit: ExitRefused}
	}
	return Verdict{Exit: ExitNotFound}
}

// devSessionCodes is rule 4a (16.10.26ae D10): a dev-session 409 is a row of
// its own, keyed on (route, body code), because a script branches on
// "someone else is developing this app" where it would not on any other
// refusal.
var devSessionCodes = []struct {
	route, code string
	exit        Exit
}{
	{"app_dev.session_open", "dev_session_held", ExitDevSessionHeld},
	{"app_dev.patch", "dev_session_held", ExitDevSessionHeld},
	{"app_dev.session_renew", "dev_session_not_live", ExitDevSessionLost},
	{"app_dev.session_renew", "dev_session_expired", ExitDevSessionLost},
}

func devSessionRow(a Answer) (Exit, bool) {
	code, ok := a.member("code")
	if !ok || a.Status != 409 {
		return 0, false
	}
	for _, row := range devSessionCodes {
		if row.route == a.Route && row.code == code {
			return row.exit, true
		}
	}
	return 0, false
}

var rules = []rule{
	{platformDisabled, exitVerdict(ExitPlatformDisabled)},
	{func(a Answer) bool { return a.Status == 401 && a.Bearer && !a.TokenRoute },
		func(Answer) Verdict { return Verdict{Refresh: true} }},
	{func(a Answer) bool {
		return a.TokenRoute && a.Status >= 400 && a.Status < 500 && a.Status != 429 && a.oauthEnvelope()
	}, exitVerdict(ExitAuthRequired)},
	{func(a Answer) bool { return a.Status == 404 && a.coded() }, codedNotFound},
	{func(a Answer) bool { _, ok := devSessionRow(a); return ok },
		func(a Answer) Verdict { exit, _ := devSessionRow(a); return Verdict{Exit: exit} }},
	{func(a Answer) bool { return a.Status == 404 && a.blank() }, exitVerdict(ExitFailure)},
	{func(a Answer) bool { return a.Status == 400 || a.Status == 403 || a.Status == 409 || a.Status == 422 },
		exitVerdict(ExitRefused)},
	{func(a Answer) bool { return a.Status == 429 || a.Status >= 500 }, exitVerdict(ExitUnavailable)},
	{func(Answer) bool { return true }, exitVerdict(ExitFailure)},
}

// Classify is the first matching rule's verdict.
func Classify(a Answer) Verdict {
	for _, r := range rules {
		if r.matches(a) {
			return r.verdict(a)
		}
	}
	return Verdict{Exit: ExitFailure}
}
