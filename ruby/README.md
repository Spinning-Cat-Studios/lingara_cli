# lingara (Ruby)

`lingara`, the Lingara developer CLI, as the RubyGems gem `lingara-cli`
(module `Lingara::CLI`): OAuth clients, apps, embeds, webhooks and
canvases from the command line, on [Thor](https://github.com/rails/thor).
It is one of seven kits held to one specification and one conformance
suite, so it behaves exactly as the others do.

```sh
gem install lingara-cli
lingara --version     # lingara <version> (ruby)
lingara login
```

Ruby 3.3 or later. Credentials live in a `0600` file, never a keychain:
see [docs/install/ruby.md](../docs/install/ruby.md).

## Working on the kit

```sh
make build-ruby                 # bundle install
make test-ruby                  # minitest, RuboCop at the budgets, the size budgets
make conformance-ruby           # every conformance case against exe/lingara
make conformance-packaged-ruby  # the built gem, installed, from outside the checkout
```

`lib/lingara/cli/assets/` holds committed copies of the shared spec files
and app templates (`make sync-kit-assets` writes them); edit the sources,
never the copies.
