package core

// PortalLine is the advice a missing developer account earns.
const PortalLine = "create your developer account at https://platform.getlingara.com"

// hintsByCode is the one line of advice a refusal's code earns (16.10.26ad
// D3, D5, D6). Keyed by code, so a reworded server sentence changes nothing.
var hintsByCode = map[string]string{
	"developer_not_found":          PortalLine,
	"developer_account_required":   PortalLine,
	"developer_terms_outdated":     "accept the updated developer terms at https://platform.getlingara.com",
	"delete_confirmation_mismatch": "the platform holds a different email for you; run `lingara whoami`",
	"invalid_scope":                "recreate this client with `lingara client create --ci`",
}

// Advise adds fail's code's line, once.
func Advise(fail *Fail) *Fail {
	if line, ok := hintsByCode[fail.Code]; ok {
		fail.addOnce(line)
	}
	return fail
}

func (f *Fail) addOnce(line string) {
	for _, h := range f.Hints {
		if h == line {
			return
		}
	}
	f.Hints = append(f.Hints, line)
}

// AdviseLingaraCode adds the portal line for a token answer whose B4
// lingara_code says the account is not a developer's yet.
func AdviseLingaraCode(fail *Fail, lingaraCode string) *Fail {
	if lingaraCode == "developer_account_required" {
		fail.addOnce(PortalLine)
	}
	return fail
}
