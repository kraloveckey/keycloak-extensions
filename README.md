# keycloak-extensions

Extensions for Keycloak 26.7. Every extension is a separate jar: install only the ones you need.

## Extensions

| Extension | What it does |
|-----------|--------------|
| [keycloak-impersonation](keycloak-impersonation/README.md) | Impersonation that works from the administrator's own browser and with a separate admin hostname. The admin console's "Impersonate" button keeps working, the user's session opens in the same browser. |
| [keycloak-access-policy](keycloak-access-policy/README.md) | One JSON policy per realm that decides who may sign in to which client: by realm roles, client roles (of any client) and groups, including subgroups. Checked on every sign-in, SSO included. |
| [keycloak-login-notification](keycloak-login-notification/README.md) | E-mails users about a sign-in after a long absence and/or from an IP address they have not used recently. Works with read-only LDAP, never blocks a sign-in. |
| [keycloak-idp-link-by-attribute](keycloak-idp-link-by-attribute/README.md) | First sign-in through Google or another identity provider: links the provider account to the existing user whose custom attribute (e.g. `workEmail`) equals the provider's e-mail. Clear error pages for not found, duplicates, disabled and already linked users. |

Each README describes what the extension does, how to set it up and how to troubleshoot it.

## Compatibility

Built and tested with Keycloak 26.7.0 and 26.7.4. The extensions use Keycloak's internal SPIs, so a jar is
guaranteed to work only with the Keycloak version it was built for; [Releases](#releases) has a build for every
Keycloak version.

## Releases

The repository's **Releases** page has one release per Keycloak version, with one jar per extension:

```
keycloak-impersonation-<version>-kc<Keycloak version>.jar         e.g. keycloak-impersonation-0.2.0-kc26.7.4.jar
keycloak-access-policy-<version>-kc<Keycloak version>.jar
keycloak-login-notification-<version>-kc<Keycloak version>.jar
keycloak-idp-link-by-attribute-<version>-kc<Keycloak version>.jar
```

Take the jars whose `kc` version matches your server.

How releases are produced (`.github/workflows/release.yml`):

- **Every day** the workflow looks up the latest stable Keycloak release. If there is no release of the current
  version for it yet, it compiles all extensions against that Keycloak version, runs the tests (the impersonation
  tests start the matching `quay.io/keycloak/keycloak` image) and publishes the release.
- **On push to `main`** that changes code or a `pom.xml` it does the same. Bump `<version>` in the root `pom.xml`
  to release new code; pushes without a version bump do not create a release (the tag already exists).
- **Manually** (Actions > Release > Run workflow) for any Keycloak version, for example the one in production:
  enter `26.7.0` and you get `v0.2.0-kc26.7.0`.
- If building or testing fails for a new Keycloak version, the workflow opens an issue "Release for Keycloak X
  failed" and stops retrying daily until a fix is pushed; the next successful release closes the issue.

Versions ending in `-SNAPSHOT` are never released. All extensions share the version in the root `pom.xml`.

## Build

JDK 21 or newer. The included `./mvnw` downloads Maven.

```sh
./mvnw clean package -DskipTests                                           # all extensions
./mvnw clean package -DskipTests -Dkeycloak.version=26.7.4                 # for another Keycloak version
./mvnw -pl keycloak-access-policy -am clean package -DskipTests            # a single extension
./mvnw clean verify                                                        # with tests
```

Every extension ends up in `<extension>/target/<extension>.jar`. The unit tests need nothing; the impersonation
integration tests need Docker (Testcontainers).

## Install

1. Copy the jars you need to `/opt/keycloak/providers/` on every Keycloak node. Remove older jars of the same
   extensions first.
2. If Keycloak starts with `--optimized` or runs from a custom image, run `kc.sh build` or rebuild the image.
3. Restart Keycloak. The log shows one `KC-SERVICES0047: <id> (...) is implementing the internal SPI ...` warning
   per installed provider: `impersonation` (twice), `access-policy`, `login-notification`,
   `idp-detect-existing-by-attribute`. They are expected.
4. Set up each extension as its README describes.

| Symptom                                       | Cause and fix                                                                             |
|-----------------------------------------------|-------------------------------------------------------------------------------------------|
| `error: release version 21 not supported`     | Maven runs on JDK 17 or older. Install JDK 21+ and make it the default.                    |
| `Could not find a valid Docker environment`   | Tests need Docker. Build with `-DskipTests`, start Docker or set `DOCKER_HOST`.            |
| No `KC-SERVICES0047` line for an extension    | The jar is not in `providers/`, or `kc.sh build` was not run for an optimized start.       |
| An authenticator is missing in "Add step"     | Same as above; restart after adding the jar.                                               |

## Try it locally

`dev/` starts Keycloak 26.7.0 with all extensions, a separate admin hostname and nginx:

```sh
./mvnw clean package -DskipTests
echo '127.0.0.1 auth.localhost admin.auth.localhost' | sudo tee -a /etc/hosts
docker compose -f dev/docker-compose.yml up
```

Admin console: `http://admin.auth.localhost:8080` (`admin`/`admin`); realm pages: `http://auth.localhost:8080`.

## Repository layout

```
pom.xml                         parent: Keycloak version, JDK, dependency and plugin versions, module list
keycloak-<extension>/           one Maven module per extension, with its own README
dev/                            docker compose + nginx for local testing
.github/                        CI, releases for every Keycloak version, Dependabot
```

## Background

- The idea of link-based impersonation comes from
  [twobiers/keycloak-impersonation-action-token](https://github.com/twobiers/keycloak-impersonation-action-token);
  keycloak-impersonation redeems the link on its own endpoint, so it behaves like Keycloak's built-in
  impersonation, including from the administrator's own browser.
- The ideas of keycloak-access-policy and keycloak-login-notification come from
  [thomasdarimont/keycloak-extension-playground](https://github.com/thomasdarimont/keycloak-extension-playground).
  Both are written anew for Keycloak 26.7: client roles of any client and groups in the policy, fail-closed on an
  invalid policy, e-mails from Keycloak's e-mail theme, state kept out of read-only LDAP.

## License

[MIT](LICENSE)

`mvnw`, `mvnw.cmd` and `.mvn/wrapper/` are the Apache Maven Wrapper, distributed under the Apache License 2.0 as
stated in their headers.
