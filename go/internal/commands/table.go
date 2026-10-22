package commands

// The kit-local arg/type table (ADR 16.10.26af D2, Go's row): what cobra's
// model does not record. Cobra keeps positionals only in Use, and pflag's
// types are coarser than the schema's (a path and an enum are both
// strings), so this one table carries each leaf's positional args and the
// flags whose type is path or enum, with the enum's values. Registration
// reads it (ExactArgs/RangeArgs, the enum check) and so does __tree, so the
// projection is never a second hand-written tree.

// arg is one positional.
type arg struct {
	name     string
	required bool
}

// flagType is a type pflag cannot say: path, or enum with its values.
type flagType struct {
	kind   string
	values []string
}

// leafSpec is one leaf's row.
type leafSpec struct {
	args  []arg
	types map[string]flagType
}

var (
	pathType   = flagType{kind: "path"}
	configOnly = map[string]flagType{"config": pathType}
)

func enum(values ...string) flagType { return flagType{kind: "enum", values: values} }

func required(names ...string) []arg {
	out := make([]arg, len(names))
	for i, n := range names {
		out[i] = arg{name: n, required: true}
	}
	return out
}

func optional(name string) []arg { return []arg{{name: name}} }

var id = required("id")

// kitTable is keyed by the leaf's path under the root, canonical names only.
var kitTable = map[string]leafSpec{
	"login":  {},
	"logout": {},
	"whoami": {},
	"apps create": {args: required("name"), types: map[string]flagType{
		"manifest": pathType, "dir": pathType, "config": pathType,
		"lang": enum("typescript", "rust", "go", "java", "kotlin", "ruby", "php"),
		"slot": enum("home.side", "plans.empty_detail"),
	}},
	"apps list":   {types: configOnly},
	"apps get":    {args: id},
	"apps update": {args: optional("id"), types: map[string]flagType{"manifest": pathType, "config": pathType}},
	"apps dev": {args: optional("name"), types: map[string]flagType{
		"tunnel": enum("cloudflared", "none"), "write-env": pathType, "config": pathType,
	}},
	"apps status":                {args: optional("name"), types: configOnly},
	"apps pin":                   {args: required("app"), types: map[string]flagType{"path": pathType, "config": pathType}},
	"apps unpin":                 {args: required("name"), types: configOnly},
	"apps delete":                {args: id},
	"apps submit":                {args: id},
	"apps versions list":         {args: id},
	"apps versions publish":      {args: required("id", "semver")},
	"apps versions withdraw":     {args: required("id", "semver")},
	"embed create":               {},
	"embed list":                 {},
	"embed get":                  {args: id},
	"embed update":               {args: id},
	"embed delete":               {args: id},
	"oauth_client create":        {types: map[string]flagType{"type": enum("confidential", "public"), "billing-mode": enum("allowance", "metered")}},
	"oauth_client list":          {},
	"oauth_client get":           {args: id},
	"oauth_client update":        {args: id},
	"oauth_client delete":        {args: id},
	"oauth_client secret create": {args: required("client_id")},
	"oauth_client secret delete": {args: required("client_id", "secret_id")},
	"webhook inbound create":     {},
	"webhook inbound list":       {},
	"webhook inbound get":        {args: id},
	"webhook inbound update":     {args: id},
	"webhook inbound delete":     {args: id},
	"webhook outbound create":    {},
	"webhook outbound list":      {},
	"webhook outbound get":       {args: id},
	"webhook outbound update":    {args: id},
	"webhook outbound replace":   {args: id},
	"webhook outbound test":      {args: id},
	"webhook outbound delete":    {args: id},
	"canvas create":              {types: map[string]flagType{"file": pathType}},
	"canvas list":                {},
	"canvas get":                 {args: id},
	"canvas update":              {args: id},
	"canvas draft":               {args: id, types: map[string]flagType{"file": pathType}},
	"canvas apply":               {args: id},
	"canvas delete":              {args: id},
	"__tree":                     {},
}
