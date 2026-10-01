# CLAUDE.md: keycloak-impersonation

Module of keycloak-extensions; build commands and the release process are in the root CLAUDE.md.

## What this is

A Keycloak extension (Keycloak 26.7 or newer) that makes admin impersonation link-based: an admin REST
endpoint returns a short-lived, single-use signed link, and the impersonation happens when a browser opens it on
the realm's own hostname. It fixes https://github.com/keycloak/keycloak/issues/10655 (separate admin hostname) and
works from the browser the admin is logged in with.

Do not route the link through `/realms/{realm}/login-actions/action-token`. `LoginActionsService` calls
`LoginActionsServiceChecks#checkIsUserValid` for every action token before the handler runs, which fails with
"You are already authenticated as different user ..." whenever the browser holds another user's SSO session in
the realm. That is why the plugin has its own redeem endpoint.

## Architecture

Registered through `src/main/resources/META-INF/services/`:

- `org.keycloak.services.resources.admin.ext.AdminRealmResourceProviderFactory` ->
  `io.github.kraloveckey.keycloak.impersonation.admin.ImpersonationAdminExtension` (id `impersonation`, stateless,
  factory and provider in one class).
  `ImpersonationAdminResource` serves `POST /admin/realms/{realm}/impersonation/users/{user-id}`: requires the
  IMPERSONATION feature and `auth.users().requireImpersonate(user)`, rejects disabled users and service accounts,
  builds an `ImpersonationLinkToken` (180 s) signed with the target realm's keys and returns
  `ImpersonationResponseDto(sameRealm, redirect)`. `sameRealm` follows the built-in rule (admin's realm == target
  realm and the admin token has a session); the admin console uses it to pick the current tab vs. a new one.
  Nothing is impersonated here.
- `org.keycloak.services.resource.RealmResourceProviderFactory` ->
  `io.github.kraloveckey.keycloak.impersonation.realm.ImpersonationRealmResourceProviderFactory` (id `impersonation`).
  `ImpersonationRedeemResource` serves `GET /realms/{realm}/impersonation/redeem?key=...`: decodes the token with
  `session.tokens().decode` (signature), checks `typ`, nonce and expiry, burns it via `session.revokedTokens()`
  (single use, same store Keycloak uses for action tokens), re-validates target user and requesting admin, logs
  out the browser's current SSO session in the realm plus the admin's own session for same-realm impersonation,
  creates the impersonated `UserSessionModel` with `IMPERSONATOR_ID`/`IMPERSONATOR_USERNAME` notes, calls
  `AuthenticationManager.createLoginCookie` and redirects 303 to the account console.

`io.github.kraloveckey.keycloak.impersonation.token.ImpersonationLinkToken` is only the token payload (a
`DefaultActionToken` subclass for its serialization and single-use key; `typ` = `impersonation-link`). There is no
action token handler.

Cookie note: `DefaultCookieProvider` writes with `setCookieIfAbsent` into a set keyed by the full cookie, so an
expire followed by a set for the same name yields two `Set-Cookie` headers and the later one wins in browsers.

`dev/nginx.conf` rewrites the admin console's built-in path `/admin/realms/{realm}/users/{id}/impersonation` to
the plugin's path on the `admin.auth.localhost` server block only. With a separate admin hostname the admin
console still logs in through the realm hostname, so the admin's SSO cookie is on the host the link points to.

## Tests

`ImpersonationTest` runs against `quay.io/keycloak/keycloak:${keycloak.version}` (passed by surefire) with the
provider classes from `target/classes`. It uses `java.net.http` with a hand-rolled cookie jar (`Browser`), logs an
admin in through the login form and checks: same-browser impersonation ends with an SSO session of the target,
the link is single use, a tampered link is rejected, a user without the impersonation role gets 403.
