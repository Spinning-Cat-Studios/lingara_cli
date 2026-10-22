# Install `lingara` with npm

```sh
npm install -g @lingara/cli
lingara --version     # lingara <version> (typescript)
```

The package is `@lingara/cli`; the command it installs is `lingara`. It needs
Node.js 22 or later.

Then sign in:

```sh
lingara login            # opens the browser
lingara login --device   # a code to enter on another machine, for SSH sessions
```

In CI, set `LINGARA_PLATFORM_CLIENT_ID` and `LINGARA_PLATFORM_CLIENT_SECRET`
to a client made with `lingara client create --ci`; nothing is stored. A
dangerous command there needs both `-y` and `--confirm <your developer
email>` on its command line.
