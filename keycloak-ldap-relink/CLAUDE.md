# CLAUDE.md: keycloak-ldap-relink

Module of keycloak-extensions; build commands and the release process are in the root CLAUDE.md.

## What this is

Links an existing local user (no federation link) to the LDAP entry with the same username, keeping the user's ID.
For applications that store Keycloak user IDs: a person's LDAP entry is deleted, a local placeholder with the old ID
is created, the person returns with a new entry (new UUID); instead of letting Keycloak import a new user, the
placeholder is linked.

## Architecture

- `LdapRelinkService#attach(userId, provider, apply)`: the only logic. Works on the user from
  `UserStoragePrivateUtil.userLocalStorage` (not the federation proxy). Chooses the realm's LDAP provider (exactly
  one enabled, or by ID/name), equality search on the provider's username attribute through
  `LDAPUtils.createQueryForUserSearch` (users DN, scope, custom filter) with limit 2, then repeats
  `LDAPStorageProvider#doImportUser` on the existing user: `setEnabled(true)`, all mappers sorted with
  `LDAPMappersComparator.sortDesc()` and `onImportUserFromLDAP(entry, user, realm, isCreate=true)`, federation link,
  `LDAP_ID`, `LDAP_ENTRY_DN`, trusted e-mail, Kerberos principal. Cache eviction on apply.
  A preview (`apply=false`) runs the same steps and calls `setRollbackOnly()`. A `ModelException` from a mapper
  (e.g. `ModelDuplicateException` for the e-mail, which `UserAttributeLDAPStorageMapper#checkDuplicateEmail`
  raises after marking rollback-only itself) becomes a problem in the result.
  The provider instance comes from the deprecated `session.getProvider(UserStorageProvider.class, model)`:
  `getComponentProvider` returns null for user storage, and the LDAP mapper factories find the provider through the
  session attribute that the deprecated method sets.
- `RelinkResult`: JSON of both endpoints and source of the tab's messages. `problems` empty = linkable.
- `admin/LdapRelinkAdminExtension` (`AdminRealmResourceProviderFactory`, id `ldap-relink`) ->
  `LdapRelinkAdminResource`: `GET users/{id}` preview, `POST users/{id}/attach` (409 + result on problems, admin
  event `UPDATE users/{id}` on success). `auth.users().requireManage(user)`; 404 vs 403 like `UsersResource`.
- `ui/LdapRelinkTab` (`UiTabProviderFactory`, id `ldap-relink`, path `/:realm/users/:id/:tab`, needs the
  experimental `declarative-ui` feature). The admin console's `PageHandler` saves a tab as one component per realm
  through the Components API (`manage-realm`), with the route params (`realm`, `id`, `tab`) merged into the config.
  All work happens in `validateConfiguration` (called by `RealmAdapter#addComponentModel` and `#updateComponent`):
  preview and refusals throw `ComponentValidationException("ldapRelinkMessage", text)` (`ldapRelinkMessage={0}`
  so the text is not parsed by `MessageFormat`); the thrown error rolls the transaction back. Success returns
  normally. The config is always cleared so nothing user-specific is stored and a realm import of the component is
  a no-op. `id` is declared as a property of type `hidden`: `RepresentationToModel#updateComponent` drops
  undeclared keys on update, and the admin console has no editor for that type, so it renders nothing.
- Labels: `theme-resources/messages/messages_en.properties` (the admin console reads its texts from the
  `messages` bundle; `admin-messages` from a jar is not picked up). The tab title is the key `ldap-relink`.
- `scripts/ldap-relink.sh`: bash + curl + jq client of the REST endpoint (service account token per user,
  preview unless `--apply`).

## Tests

`LdapRelinkTest` starts an UnboundID in-memory LDAP server in the test JVM (`entryUUID`, vendor "other") and
`quay.io/keycloak/keycloak:${keycloak.version}` with the classes from target/classes and `declarative-ui`;
Keycloak reaches LDAP through `Testcontainers.exposeHostPorts` / `host.testcontainers.internal`. Placeholders are
created by partial import (keeps the ID) before their LDAP entry exists. Covered: preview changes nothing, attach
keeps ID and role mappings and allows LDAP sign-in, exact username match (`test` vs `test.a`/`a.test`), e-mail
conflict refused without changes, `view-users` gets 403 and `manage-users` works, the tab's create/update path
(preview, wrong confirmation, attach, empty stored config).

`-Dldaprelink.it.keycloak=http://localhost:8080` runs the same tests against a running Keycloak (admin/admin in
master, jar installed, `declarative-ui` on) with LDAP on localhost.
