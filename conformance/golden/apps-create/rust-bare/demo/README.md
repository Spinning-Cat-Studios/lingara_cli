# Demo

A Lingara app.

A Lingara app with both halves in code: `src/bin/manifest.rs` prints the
manifest, and `src/bin/app.rs` verifies and answers on the kit's own
primitives (`App::check_method`, `App::handle`).

```sh
lingara login          # once
lingara apps dev       # serve it from this machine to your own install, until Ctrl+C
lingara apps update    # run `cargo run --quiet --bin manifest` and push what it prints to your dev version
```

`lingara apps dev` opens a tunnel, points your private dev version at it, and
runs the app with `LINGARA_APP_SECRET` set for this session only. Nothing
secret is written to disk. When the app has a real address, set it in
`src/bin/manifest.rs` and cut a version with `lingara apps update --version 1.0.0`.

`cargo test` signs a fake render offline and checks a card comes back.
