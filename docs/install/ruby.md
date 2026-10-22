# Install `lingara` with RubyGems

```sh
gem install lingara-cli
lingara --version     # lingara <version> (ruby)
```

The gem is `lingara-cli`; the command it installs is `lingara`. It needs
Ruby 3.3 or later.

Then sign in:

```sh
lingara login            # opens the browser
lingara login --device   # a code to enter on another machine, for SSH sessions
```

This kit keeps your credentials in a file only, never in the system
keychain: `credentials.json` in `$XDG_CONFIG_HOME/lingara` (else
`~/.config/lingara`, or `%APPDATA%\lingara` on Windows), readable by you
alone (mode `0600` in a `0700` directory). The first sign-in says where it
stored them. `LINGARA_KEYRING=keychain` is refused rather than ignored; use
the Rust, TypeScript, Go, Java or Kotlin kit if you need the keychain.

In CI, set `LINGARA_PLATFORM_CLIENT_ID` and `LINGARA_PLATFORM_CLIENT_SECRET`
to a client made with `lingara client create --ci`; nothing is stored. A
dangerous command there needs both `-y` and `--confirm <your developer
email>` on its command line.

`lingara apps dev` runs on macOS and Linux. On Windows this kit does not
support it yet and says so; the Go, Rust and TypeScript kits do.
