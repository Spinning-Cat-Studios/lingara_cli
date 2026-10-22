# {{display_name}}

{{description}}

A Lingara app with both halves in Go: `cmd/manifest` prints the manifest, and
`cmd/app` serves the kit's `net/http` handler, the Go kit's own primitive.

```sh
go mod tidy            # once: fetch the kit and write go.sum
lingara login          # once
lingara apps dev       # serve it from this machine to your own install, until Ctrl+C
lingara apps update    # run `go run ./cmd/manifest` and push what it prints to your dev version
```

`lingara apps dev` opens a tunnel, points your private dev version at it, and
runs `go run ./cmd/app` with `LINGARA_APP_SECRET` set for this session only.
Nothing secret is written to disk. When the app has a real address, set it in
`cmd/manifest/main.go` and cut a version with `lingara apps update --version 1.0.0`.

`go test ./...` signs a fake render offline and checks a card comes back.
