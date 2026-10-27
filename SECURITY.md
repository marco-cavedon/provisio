# Security policy

Provisio is a showcase project and is not intended for production use (see the [README](README.md#license)). It
still aims to be secure by default: credentials stay out of the repository, every host port is bound to `127.0.0.1`,
and each service connects with the least privilege it needs.

## Supported versions

Only the latest release receives fixes. Older tags are not patched: a fix ships in the next release.

## Reporting a vulnerability

Please report it privately through GitHub's
[private vulnerability reporting](https://github.com/marco-cavedon/provisio/security/advisories/new), not in a public
issue. Include the affected version or commit, the steps to reproduce and the impact you expect.

This is a one-person project: reports are read on a best-effort basis, usually within a week.

## Third-party components

Vulnerabilities in Camunda, MongoDB, `mongot`, the JDK or any other dependency belong to their own projects; please
report them there. Provisio picks up their fixes through dependency updates.
