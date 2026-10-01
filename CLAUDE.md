# CLAUDE.md

Guidance for Claude Code (claude.ai/code) in this repository.

## What this is

keycloak-extensions: Keycloak extensions (Keycloak 26.7 or newer), one Maven module and one jar each, sharing a parent `pom.xml`
(Keycloak version, JDK 21, dependency/plugin versions, one version for all modules).

- `keycloak-impersonation`: admin REST + realm resource providers for link-based impersonation. See
  `keycloak-impersonation/CLAUDE.md`.
- `keycloak-access-policy`: `Authenticator` "Access policy" (id `access-policy`).
- `keycloak-login-notification`: `Authenticator` "Login notification e-mail" (id `login-notification`).
- `keycloak-idp-link-by-attribute`: first-broker-login `Authenticator` "Detect existing broker user by attribute"
  (id `idp-detect-existing-by-attribute`).

## Commands

```sh
./mvnw clean package -DskipTests                               # all jars: <module>/target/<module>.jar
./mvnw -pl keycloak-access-policy -am clean verify             # one module with its tests
./mvnw clean verify -Dkeycloak.version=<keycloak-version>      # against another Keycloak
docker compose -f dev/docker-compose.yml up                    # Keycloak (KEYCLOAK_VERSION) + all jars + nginx
```

Keycloak artifacts are declared with `${keycloak.version}` directly (no third-party BOM: those are not published for
every Keycloak patch). Unit tests need nothing, the impersonation integration tests need Docker.

## keycloak-access-policy

- `AccessPolicy` parses and validates the JSON (Jackson, unknown fields rejected) and evaluates it against an
  `AccessSubject` (pure logic, unit-tested in `AccessPolicyTest`). Client entries starting with `^` are full-match
  regexes. Any role or group of any applicable rule grants (OR); a rule without conditions blocks; clients without
  rules follow `unlistedClients` (allow|deny).
- `UserAccessSubject`: `UserModel#hasRole` (composites + group roles), `RoleUtils.isMember` (subgroups count),
  `KeycloakModelUtils.findGroupByPath`.
- `AccessPolicyAuthenticator`: invalid policy -> deny (fail closed) + error log. Denial: event `access_denied` with
  detail `access_policy_reason`; browser flows get an error page (403), the password grant (flow path `token`) gets
  an OAuth2 JSON error. Must sit after a REQUIRED sub-flow holding the browser alternatives so it also runs for SSO.

## keycloak-login-notification

- `LoginNotificationRules`: pure decision logic (inactivity, new IP, recent-IP list), unit-tested.
- `LoginHistory`: state in user attributes `login-notification.last-login` / `login-notification.recent-ips`,
  written via `UserStoragePrivateUtil.userLocalStorage` (+ user cache eviction) or, for non-imported federated users,
  `UserStorageUtil.userFederatedStorage`. Never through the federation proxy: READ_ONLY LDAP throws on any attribute
  write.
- `LoginNotificationAuthenticator`: always `context.success()`; mail via `EmailTemplateProvider` with
  `theme-resources/templates/{html,text}/login-notification.ftl` and `theme-resources/messages/messages_{en,uk}`
  (themes and realm localization overrides take precedence).

## keycloak-idp-link-by-attribute

- `IdpDetectByAttributeAuthenticator` extends `AbstractIdpAuthenticator`: lower-cased provider e-mail,
  `searchForUserByUserAttributeStream(...).limit(10)`, decision in `LinkDecision` (pure, unit-tested), success puts
  `ExistingUserInfo` into the `EXISTING_USER_INFO` auth note for `idp-auto-link`; it never writes or links itself.
- Refusals: `failureChallenge(INVALID_USER)` with an error page keyed `idpAttr*` (messages in
  `theme-resources/messages`, realm overrides win) and event errors per `LinkDecision.Result`. Also refuses
  `email_verified=false` from the OIDC ID/access token (`requireVerifiedEmail`, default on). Misconfiguration:
  `failure(INTERNAL_ERROR)` with an `internalServerError` page, not Keycloak's misleading default.

## Releases

`.github/workflows/release.yml` + `.github/scripts/plan-release.sh`: daily, on push to main and manually; one GitHub
release per (root version, Keycloak version), tag `v<version>-kc<keycloak>`, assets
`<module>-<version>-kc<keycloak>.jar`. Details in README.md.
