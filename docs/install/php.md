# Install `lingara` with Composer, or as a PHAR

```sh
composer global require spinningcatstudios/lingara-cli
lingara --version     # lingara <version> (php)
```

The package is `spinningcatstudios/lingara-cli`; the command it installs is
`lingara`, in Composer's global `vendor/bin` (put it on your `PATH`:
`composer global config bin-dir --absolute` prints where it is). It needs
PHP 8.2 or later.

If you would rather not use Composer's global directory, every release on
<https://github.com/Spinning-Cat-Studios/lingara_cli/releases> carries
`lingara.phar`, the same CLI in one file:

```sh
php lingara.phar --version
chmod +x lingara.phar && mv lingara.phar /usr/local/bin/lingara
```

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

`lingara apps dev` runs on macOS and Linux, and there it needs the PHP
extensions `pcntl` and `posix`, which it uses to stop your app and the tunnel
cleanly when you press Ctrl-C (`php -m` lists what is loaded; most Linux and
Homebrew builds have both). Without them it exits and names the missing one,
rather than leaving processes behind. Without `pcntl`, Ctrl-C also ends any
other command at once instead of returning exit code 130. On Windows this kit
does not support `apps dev` yet and says so; the Go, Rust and TypeScript kits
do.
