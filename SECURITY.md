# Security

## Reporting a vulnerability

Email **support@getlingara.com** with "Security" in the subject line, and
describe the issue and the steps to reproduce it. Please do not open a public
issue for a vulnerability.

## Credentials

`lingara login` stores a refresh token for your developer account; in CI,
`lingara` exchanges an OAuth client's id and secret for a short-lived token
and keeps it in memory only. `lingara` never logs a client secret, an access
token or a refresh token, at any verbosity; a variant that does is a
vulnerability, and we want to hear about it.

A secret `lingara` creates is printed once. Keep it out of source control and
shell history.

## Deleting things

Every command that deletes or replaces something asks twice, or needs both
`-y` and `--confirm <your developer email>`. Confirmation is never read from
the environment or a config file, so a script cannot delete by accident.
