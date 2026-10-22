// Package httpx is every request the kit sends: the routes, spelled once,
// the one function that adds contract B5's headers and D6's retries, and
// the net/http transport under it (ADR 16.10.26af D1: the language's
// standard client, never the lingara API libraries).
package httpx

import "strings"

// Every path this kit sends, keyed by conformance/routes.toml's logical
// names (ADR 16.10.26ad D2). The mock matches by path, so a spelling that
// drifts from that file meets a 599 and fails conformance.

// Base is which base a path is under: the API, or the portal's browser pages.
type Base int

// The two bases.
const (
	BaseAPI Base = iota
	BasePortal
)

// Route is one logical route.
type Route struct {
	// Name is the logical name: what `unexpected response from {route}` names.
	Name   string
	Method string
	Path   string
	Base   Base
}

// IsToken is oauth.token and oauth.device_authorization: no bearer, and
// their RFC envelope is B2 rule 3's.
func (r Route) IsToken() bool {
	return r.Name == "oauth.token" || r.Name == "oauth.device_authorization"
}

// IsPlatform is a route under /platform/, where the dark 404 is spoken.
func (r Route) IsPlatform() bool {
	return strings.HasPrefix(r.Path, "/platform/")
}

const (
	objectsPath = "/platform/objects"
	objectPath  = "/platform/objects/{id}"
	canvasPath  = "/v1/canvases/{id}"
	devPath     = "/platform/objects/{id}/dev"
	sessionPath = "/platform/objects/{id}/dev/session"
	versionPath = "/platform/objects/{id}/versions/{semver}"
)

var fixed = map[string]Route{
	"oauth.authorize":            {Method: "GET", Path: "/oauth/authorize", Base: BasePortal},
	"oauth.device_authorization": {Method: "POST", Path: "/oauth/device_authorization"},
	"oauth.token":                {Method: "POST", Path: "/oauth/token"},
	"oauth.revoke":               {Method: "POST", Path: "/oauth/revoke"},
	"developer.me":               {Method: "GET", Path: "/platform/developer"},
	"canvas.list":                {Method: "GET", Path: "/v1/canvases"},
	"canvas.create":              {Method: "POST", Path: "/v1/canvases"},
	"canvas.get":                 {Method: "GET", Path: canvasPath},
	"canvas.rename":              {Method: "PATCH", Path: canvasPath},
	"canvas.put_draft":           {Method: "PUT", Path: "/v1/canvases/{id}/draft"},
	"canvas.dry_run":             {Method: "POST", Path: "/v1/canvases/dry-run"},
	"canvas.activate":            {Method: "POST", Path: "/v1/canvases/{id}/activate"},
	// ?kind=canvas&runtime_id=… is the caller's query (D2).
	"canvas.lookup": {Method: "GET", Path: objectsPath},
	// The dev version and its session (16.10.26v §12; 16.10.26ae R1–R3).
	"app_dev.get":           {Method: "GET", Path: devPath},
	"app_dev.patch":         {Method: "PATCH", Path: devPath},
	"app_dev.session_open":  {Method: "POST", Path: sessionPath},
	"app_dev.session_renew": {Method: "POST", Path: sessionPath + "/{session_id}/renew"},
	"app_dev.session_end":   {Method: "DELETE", Path: sessionPath + "/{session_id}"},
}

// Fixed is a route that is not a kind's /platform/objects family, by name.
func Fixed(name string) Route {
	r := fixed[name]
	r.Name = name
	return r
}

// objectOps is one operation of the /platform/objects family (16.10.26v §8).
var objectOps = map[string][2]string{
	"list":           {"GET", objectsPath},
	"get":            {"GET", objectPath},
	"create":         {"POST", objectsPath},
	"rename":         {"PATCH", objectPath},
	"version_get":    {"GET", versionPath},
	"version_put":    {"PUT", versionPath},
	"version_create": {"POST", "/platform/objects/{id}/versions"},
	"publish":        {"POST", versionPath + "/publish"},
	"versions":       {"GET", "/platform/objects/{id}/versions"},
	"submit":         {"POST", versionPath + "/submit"},
	"withdraw":       {"POST", versionPath + "/withdraw"},
	"delete":         {"DELETE", objectPath},
	"delete_version": {"DELETE", versionPath},
	"secret_create":  {"POST", "/platform/objects/{id}/secrets"},
	"secret_delete":  {"DELETE", "/platform/objects/{id}/secrets/{secret_id}"},
	"enable":         {"POST", "/platform/objects/{id}/enable"},
	"disable":        {"POST", "/platform/objects/{id}/disable"},
	"test":           {"POST", "/platform/objects/{id}/test"},
}

// Object is `<word>.<op>`: Object("webhook_outbound", "get") is
// webhook_outbound.get, GET /platform/objects/{id}.
func Object(word, op string) Route {
	return ObjectNamed(word+"."+op, op)
}

// ObjectNamed is the same path under another name: canvas.object_get is
// <word>.get's path, renamed because canvas.get is the /v1 read (D2).
func ObjectNamed(name, op string) Route {
	spelled := objectOps[op]
	return Route{Name: name, Method: spelled[0], Path: spelled[1], Base: BaseAPI}
}
