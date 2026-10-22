# lingara, the Lingara developer CLI (PHP)

`lingara` creates and manages the things a developer builds on Lingara: OAuth
clients, apps, embeds, inbound and outbound webhooks, and canvases.

```sh
composer global require spinningcatstudios/lingara-cli
```

or download `lingara.phar` from a release of
<https://github.com/Spinning-Cat-Studios/lingara_cli/releases>. PHP 8.2 or
later; `apps dev` also needs the `pcntl` and `posix` extensions.

## Sign in

```sh
lingara login            # the browser, through a loopback redirect (PKCE)
lingara login --device   # a code to enter elsewhere, for SSH sessions
lingara whoami
lingara logout
```

Credentials go to `~/.config/lingara/credentials.json` (mode `0600`) only:
this kit has no keychain support, and `LINGARA_KEYRING=keychain` is refused.
`--profile <name>` (or `LINGARA_PROFILE`) keeps several apart.

## CI

Make a CI client once, then give the job its id and secret:

```sh
lingara client create --name 'Release pipeline' --ci
export LINGARA_PLATFORM_CLIENT_ID=… LINGARA_PLATFORM_CLIENT_SECRET=…
```

Every command then signs in with those, and stores nothing.

## Deleting asks twice

A delete asks `y/n`, then asks you to type your developer email. Away from a
terminal it refuses unless both flags are on the command line:

```sh
lingara client delete <id> -y --confirm you@example.com
```

Neither flag comes from the environment or configuration.

## Output

Tables for people; `--json` for scripts, which prints the platform's JSON
unchanged (a list as one array of every page). Exit codes are listed in the
reference and are the same in every language of this CLI.
