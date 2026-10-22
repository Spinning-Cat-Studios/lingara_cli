# {{display_name}}

{{description}}

A Lingara app with both halves in PHP: `manifest.php` prints the manifest from
the kit's builder, and `public/index.php` answers through the kit's PSR-15
handler, PHP's own primitive for a request.

```sh
composer install       # once
lingara login          # once
lingara apps dev       # serve it from this machine to your own install, until Ctrl+C
lingara apps update    # run `php manifest.php` and push what it prints to your dev version
```

`lingara apps dev` opens a tunnel, points your private dev version at it, and
runs `php server.php` with `LINGARA_APP_SECRET` set for this session only.
Nothing secret is written to disk. When the app has a real address, set it in
`manifest.php` and cut a version with `lingara apps update --version 1.0.0`.

`composer test` signs a fake render offline and checks a card comes back.
