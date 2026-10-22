# lingara, the Lingara developer CLI (Go)

`lingara` creates and manages the things a developer builds on Lingara: OAuth
clients, apps, embeds, inbound and outbound webhooks, and canvases. This is
the Go build: one static binary, no toolchain needed to run it. Install it
from Homebrew or a release archive (`docs/install/go.md`).

## Sign in

```sh
lingara login            # the browser, through a loopback redirect (PKCE)
lingara login --device   # a code to enter elsewhere, for SSH sessions
lingara whoami
lingara logout
```

Credentials go to the OS keychain, or to `~/.config/lingara/credentials.json`
(mode `0600`) where there is none. `LINGARA_KEYRING=file` or `=keychain`
chooses; `--profile <name>` (or `LINGARA_PROFILE`) keeps several apart.

## CI

Make a CI client once, then give the job its id and secret:

```sh
lingara client create --name 'Release pipeline' --ci
export LINGARA_PLATFORM_CLIENT_ID=… LINGARA_PLATFORM_CLIENT_SECRET=…
```

## Building it

The module is `go/`, built on cobra. From the repository root:

```sh
make build-go            # go/dist/lingara, VERSION stamped in by -ldflags
make test-go             # go vet (+ Windows), golangci-lint, go test
make conformance-go      # every conformance case against the built binary
```

`internal/assets/_shared/` is the committed copy of the shared spec files
and app templates that `make sync-kit-assets` writes; the binary embeds it,
so it reads nothing outside `go/`. Its Go templates are not part of this
module's build (their imports are `{{name}}` placeholders); the leading
underscore is what keeps them out, since the go tool skips `_`-prefixed
directories in `./...` and `go mod tidy`.
