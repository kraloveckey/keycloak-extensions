# keycloak-impersonation

Part of [keycloak-extensions](../README.md).

A Keycloak plugin that replaces the built-in "impersonate now, set a cookie on the admin response" flow
with a link-based one. The admin endpoint returns a short-lived, signed, single-use link. Impersonation happens
when the browser opens that link on the realm's own hostname.

Works from the browser the administrator is logged in with, no incognito window or second browser needed.
Tested with Keycloak 26.7.0 and 26.7.4; a release is built and tested automatically for every new Keycloak
version (see [Releases](../README.md#releases)).

## Problems it solves

**Separate admin hostname** ([keycloak/keycloak#10655](https://github.com/keycloak/keycloak/issues/10655)).
The built-in endpoint sets `KEYCLOAK_IDENTITY`/`KEYCLOAK_SESSION` on the response to the admin console's XHR.
When the admin console runs on another hostname than the realm (`KC_HOSTNAME_ADMIN`), the cookies land on the
admin hostname and are useless on the realm hostname. The admin ends up on the account page logged in as nobody.

**Same browser, same realm.** Redeeming such a link through Keycloak's generic action-token endpoint
(`/realms/{realm}/login-actions/action-token`) does not work either. `LoginActionsService` runs
`LoginActionsServiceChecks#checkIsUserValid` for every action token before the handler is called, and that
check refuses the request when the browser already holds an SSO session of another user in the realm:

```
You are already authenticated as different user 'admin' in this session. Please sign out first.
```

That is always the case when an admin impersonates a user of the realm they are logged into, and with a separate
admin hostname the admin's SSO cookie sits exactly on the realm hostname the link points to. An SPI cannot opt out
of that check, so the plugin redeems the link on its own realm endpoint, `/realms/{realm}/impersonation/redeem`.

## How it works

1. The admin console calls `POST /admin/realms/{realm}/users/{id}/impersonation`. A reverse proxy rewrites it
   to the plugin (see [Routing](#routing-the-admin-console-to-the-plugin)).
2. The plugin checks the impersonate permission, signs a token (180 s, single use) with the target realm's
   keys and returns `{ "sameRealm": ..., "redirect": "https://<realm host>/realms/{realm}/impersonation/redeem?key=..." }`.
   Nothing is impersonated yet, no cookie is set.
3. The admin console opens `redirect`: in the current tab when `sameRealm` is true, in a new tab otherwise.
4. The redeem endpoint verifies the token (signature, type, expiry, not used before, target user enabled and not
   a service account, requesting admin still enabled) and then does what the built-in endpoint does:
   logs out the SSO session the browser holds in that realm and, for same-realm impersonation, the admin's own
   session; creates a user session for the target with the `IMPERSONATOR_ID`/`IMPERSONATOR_USERNAME` notes;
   writes the identity cookie in this response; records an `IMPERSONATE` event and redirects (303) to the
   account console.

Because step 4 is served under `/realms/{realm}/` on the frontend hostname, the cookies land exactly where the
realm reads them, whatever hostname the admin console runs on.

## Behaviour to know about

### What happens to the admin's own session

A browser holds one SSO session per realm. Which session gets replaced depends on where the admin is logged in:

| Admin logged into                     | Impersonated user in | Admin console after impersonation                               |
|---------------------------------------|----------------------|-----------------------------------------------------------------|
| `master` (or any other realm)         | `demo`               | Keeps working; the link opens in a new tab                      |
| `demo` (the same realm as the user)   | `demo`               | The admin's session ends; the link opens in the same tab        |

In the second case sign in again when you are done. The built-in "Impersonate" button behaves the same way; it
is how Keycloak sessions work, not a limitation of this plugin. To keep the admin console open while
impersonating users of a realm, use an admin account from `master` (with impersonation rights for that realm),
or open the admin console in another browser profile.

Whatever SSO session the browser already had in the target realm (for example a previous impersonation) is logged
out as well, including back-channel logout to its clients.

### Other things to know

- The link expires after 180 seconds and works once. Request a new one for another attempt.
- The link is meant for a browser. A backend can request it with any token that has impersonate permission and
  hand it to a browser.
- The admin console's "Impersonate" button only uses the plugin if the proxy rewrite below is in place.
  Without it the built-in endpoint answers.
- Applications the user is already logged into in this browser (their own cookies, e.g. a Moodle session) are
  not touched. Log out of them before opening them as the impersonated user.

## Routing the admin console to the plugin

The admin console calls the built-in path `/admin/realms/{realm}/users/{user-id}/impersonation`. A plugin
cannot take over a core path, so this one listens on `/admin/realms/{realm}/impersonation/users/{user-id}`.
A rewrite in the reverse proxy connects the two. The redeem link is a normal realm URL and needs no rule.

**nginx** (in the `server {}` block, not inside a `location`: `rewrite ... last` runs before a location is chosen)

```nginx
rewrite ^/admin/realms/([^/]+)/users/([^/]+)/impersonation$ /admin/realms/$1/impersonation/users/$2 last;
```

If Keycloak runs with the legacy `/auth` context path, the rule needs the prefix:

```nginx
rewrite ^/auth/admin/realms/([^/]+)/users/([^/]+)/impersonation$ /auth/admin/realms/$1/impersonation/users/$2 last;
```

To find out which one your setup needs, ask both paths without a token. The one that answers 401 is correct,
the other one gives 404:

```sh
curl -sk -o /dev/null -w '%{http_code}\n' -X POST https://keycloak.example.com/admin/realms/master/users/x/impersonation
curl -sk -o /dev/null -w '%{http_code}\n' -X POST https://keycloak.example.com/auth/admin/realms/master/users/x/impersonation
nginx -t && systemctl reload nginx
```

**Traefik** (dynamic config)

```yaml
http:
  middlewares:
    impersonation-rewrite:
      replacePathRegex:
        regex: "^/admin/realms/([^/]+)/users/([^/]+)/impersonation$"
        replacement: "/admin/realms/$1/impersonation/users/$2"
```

**Caddy**

```
@impersonate path_regexp impersonate ^/admin/realms/([^/]+)/users/([^/]+)/impersonation$
rewrite @impersonate /admin/realms/{re.impersonate.1}/impersonation/users/{re.impersonate.2}
```

**HAProxy**

```
acl is_impersonate path_reg ^/admin/realms/[^/]+/users/[^/]+/impersonation$
http-request set-path %[path,regsub(^/admin/realms/([^/]+)/users/([^/]+)/impersonation$,/admin/realms/\1/impersonation/users/\2)] if is_impersonate
```

The plugin's own paths get the same `/auth` prefix when Keycloak uses it.

## Releases

Jars are published on the repository's Releases page for every Keycloak version, as
`keycloak-impersonation-<version>-kc<Keycloak version>.jar`; take the one matching your server. How releases are
built is described in the [repository README](../README.md#releases).

## Build and install

### Build

Requirements: JDK 21 or newer and Maven (the included `./mvnw` downloads it).

```sh
# from the repository root
./mvnw -pl keycloak-impersonation -am clean package -DskipTests                            # this extension only
./mvnw -pl keycloak-impersonation -am clean package -DskipTests -Dkeycloak.version=26.7.4  # for another Keycloak
./mvnw -pl keycloak-impersonation -am clean verify                                         # plus integration tests (Docker)
```

The jar is `keycloak-impersonation/target/keycloak-impersonation.jar`.

### Install

1. Remove older jars of this plugin from `/opt/keycloak/providers/`, including `keycloak-impersonation-plugin*.jar`
   from before it moved into keycloak-extensions. If an action-token based impersonation
   extension was installed before, remove its jar too: both register `/admin/realms/{realm}/impersonation`.
2. Copy the new jar into `/opt/keycloak/providers/` (on every node of a cluster).
3. If Keycloak starts with `--optimized` (or runs from a custom image), run `kc.sh build` or rebuild the image.
4. Restart Keycloak and add the reverse-proxy rewrite (next section).

At startup Keycloak logs two `KC-SERVICES0047: impersonation (...) is implementing the internal SPI ...`
warnings, one per endpoint. They are expected and confirm the plugin is loaded.

### Troubleshooting

| Symptom                                                                 | Cause and fix                                                                                      |
|-------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------|
| `error: release version 21 not supported`                               | Maven runs on JDK 17 or older. Install JDK 21+ and make it the default (`update-alternatives`).    |
| `Could not find a valid Docker environment` in tests                    | No Docker for Testcontainers. Build with `-DskipTests`, start Docker, or set `DOCKER_HOST`.        |
| No `KC-SERVICES0047 ... impersonation` lines at startup                 | Jar not in `providers/`, or `kc.sh build` was not run for an optimized start.                      |
| Clicking "Impersonate" still shows "already authenticated as different user" | The built-in endpoint or an old extension answered: check the proxy rewrite and remove old jars. |
| Link page "Invalid impersonation link."                                 | The link was cut or altered, or it was issued for another realm.                                   |
| Link page "already been used" / "expired"                               | Links work once and for 180 seconds. Click "Impersonate" again.                                    |

### Try it locally

`dev/` in the repository root has a Keycloak 26.7.0 with a separate admin hostname behind nginx, with the rewrite
in place and all extensions of the repository loaded:

```sh
./mvnw clean package -DskipTests
echo '127.0.0.1 auth.localhost admin.auth.localhost' | sudo tee -a /etc/hosts
docker compose -f dev/docker-compose.yml up
```

Admin console: `http://admin.auth.localhost:8080` (`admin`/`admin`). Realm pages and impersonation links are
served on `http://auth.localhost:8080`, which does not expose `/admin/`.

## API

### `POST /admin/realms/{realm}/impersonation/users/{user-id}`

Bearer token of a caller with impersonate permission on the user (the standard admin permission check,
fine-grained permissions included). The request body is ignored.

`200 OK`

```json
{
  "sameRealm": true,
  "redirect": "https://keycloak.example.com/realms/{realm}/impersonation/redeem?key=..."
}
```

| Field       | Description                                                                                           |
|-------------|-------------------------------------------------------------------------------------------------------|
| `sameRealm` | `true` when the caller is logged into `{realm}` itself; the admin console then uses the current tab   |
| `redirect`  | Single-use link, valid 180 s, on the realm's frontend hostname                                        |

| Status | Condition                                                                            |
|--------|--------------------------------------------------------------------------------------|
| `400`  | User disabled, or a service account                                                  |
| `403`  | No impersonate permission, or unknown user id and the caller may not query users     |
| `404`  | Unknown user id (callers that may query users)                                       |
| `501`  | Impersonation feature disabled on the server                                         |

### `GET /realms/{realm}/impersonation/redeem?key=...`

Opened by the browser. On success `303 See Other` to `/realms/{realm}/account` with the new identity cookie.
On failure Keycloak's error page with `400` (invalid, expired or used link, target user missing or disabled,
requesting admin missing or disabled) and an `IMPERSONATE_ERROR` event.

### Token claims

The link carries a JWT signed with the realm's HMAC key (`typ` = `impersonation-link`).

| Claim          | Meaning                                                                   |
|----------------|---------------------------------------------------------------------------|
| `sub`          | User to impersonate                                                       |
| `imp_realm`    | Realm the admin is logged into                                            |
| `imp_id`       | Id of the requesting admin                                                |
| `imp_name`     | Username of the requesting admin                                          |
| `imp_sid`      | Admin's session id, only for same-realm impersonation (logged out on use) |
| `redirect`     | Where the browser goes after redemption                                   |
| `exp`, `nonce` | Expiry and the value that makes the token single-use                      |

## Background

The idea of link-based impersonation comes from
[twobiers/keycloak-impersonation-action-token](https://github.com/twobiers/keycloak-impersonation-action-token).
This plugin redeems the link on its own realm endpoint instead of Keycloak's action-token endpoint, so it behaves
like Keycloak's built-in impersonation, including from the browser the administrator is logged in with.

## License

[MIT](../LICENSE)
