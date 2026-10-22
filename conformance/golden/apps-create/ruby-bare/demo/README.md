# Demo

A Lingara app.

A Lingara app with both halves in Ruby: `manifest.rb` prints the manifest from
the kit's builder, and `server.rb` answers on `App#handle`, the kit's own
primitive, rather than its Rack adapter.

```sh
bundle install         # once
lingara login          # once
lingara apps dev       # serve it from this machine to your own install, until Ctrl+C
lingara apps update    # run `bundle exec ruby manifest.rb` and push what it prints to your dev version
```

`lingara apps dev` opens a tunnel, points your private dev version at it, and
runs `bundle exec ruby server.rb` with `LINGARA_APP_SECRET` set for this
session only. Nothing secret is written to disk. When the app has a real
address, set it in `manifest.rb` and cut a version with
`lingara apps update --version 1.0.0`.

`bundle exec ruby -Itest test/render_test.rb` signs a fake render offline and
checks a card comes back.
