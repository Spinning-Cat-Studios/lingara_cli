# Install `lingara` (the Go build)

The Go build of `lingara` is a single static binary for macOS, Linux and
Windows, on `amd64` and `arm64`. It needs no Go toolchain.

## Homebrew

```sh
brew install spinning-cat-studios/tap/lingara
lingara --version     # lingara <version> (go)
```

## Download an archive

Each release on
<https://github.com/Spinning-Cat-Studios/lingara_cli/releases> carries six
archives, `lingara_<tag>_<os>_<arch>` (`.tar.gz`, and `.zip` for Windows),
where `<tag>` is the release's tag, `v` included, and a `SHA256SUMS` file
covering them. Download the archive for your machine and `SHA256SUMS`, check
the archive, then unpack it onto your `PATH`:

```sh
version=v0.1.0-alpha.1   # the release's tag
os=darwin            # darwin or linux
arch=arm64           # arm64 or amd64
base=https://github.com/Spinning-Cat-Studios/lingara_cli/releases/download/$version
curl -fLO "$base/lingara_${version}_${os}_${arch}.tar.gz"
curl -fLO "$base/SHA256SUMS"
grep "lingara_${version}_${os}_${arch}.tar.gz" SHA256SUMS | shasum -a 256 -c -
tar -xzf "lingara_${version}_${os}_${arch}.tar.gz" lingara
sudo mv lingara /usr/local/bin/
lingara --version     # lingara <version> (go)
```

On Linux, `sha256sum -c -` does what `shasum -a 256 -c -` does above. On
Windows, unpack the `.zip`, put `lingara.exe` in a directory on your `PATH`,
and compare `Get-FileHash -Algorithm SHA256` with the archive's line in
`SHA256SUMS`.

On macOS, a binary downloaded with a browser is quarantined, and macOS
refuses to run it. Clear the flag once:

```sh
xattr -d com.apple.quarantine /usr/local/bin/lingara
```

An archive fetched with `curl`, as above, is not quarantined, and neither
is the Homebrew install.

## Then sign in

```sh
lingara login            # opens the browser
lingara login --device   # a code to enter on another machine, for SSH sessions
```

Credentials go to the OS keychain, or to `~/.config/lingara/credentials.json`
(mode `0600`) where there is none.

In CI, set `LINGARA_PLATFORM_CLIENT_ID` and `LINGARA_PLATFORM_CLIENT_SECRET`
to a client made with `lingara client create --ci`; nothing is stored. A
dangerous command there needs both `-y` and `--confirm <your developer
email>` on its command line.
