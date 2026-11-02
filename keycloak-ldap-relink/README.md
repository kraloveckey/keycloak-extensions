# keycloak-ldap-relink

Part of [keycloak-extensions](../README.md).

Links an existing **local** user to the LDAP entry with the same username, and the user **keeps its ID**, roles,
groups, consents and everything else stored for it. From then on it is an ordinary LDAP user: password from LDAP,
attributes from the LDAP mappers, periodic sync.

Typical case: applications that store Keycloak user IDs. Someone leaves, their LDAP / Active Directory account is
disabled and later deleted, so Keycloak removes the imported user. To keep the ID known to those applications, a
local placeholder user with the same ID is created. When the person comes back, LDAP gets a new entry with the old
username but a new UUID (`objectGUID`, `entryUUID`), and Keycloak would import it as a new user with a new ID. With
this extension the placeholder is linked to the new entry instead: no database edits, no restart.

## What linking does

The user ends up exactly as Keycloak's own LDAP import would have created it from that entry, the same steps in the
same order, only on the existing user:

1. enables the user;
2. runs all LDAP mappers of the provider in import mode: username, e-mail, first and last name and other attribute
   mappers write the LDAP values (new values replace old ones), group and role mappers in `IMPORT` mode add the
   LDAP memberships, the MSAD account controls mapper sets enabled / required actions from Active Directory;
3. sets the federation link to the provider and the attributes `LDAP_ID` (the entry's UUID) and `LDAP_ENTRY_DN`;
4. marks the e-mail verified if the provider trusts e-mails; sets the Kerberos principal if Kerberos is configured;
5. evicts the user from the cache (all cluster nodes).

Everything stored for the user that no mapper touches stays: ID, creation time, role mappings, group memberships,
consents, manually set attributes.

Everything happens in one transaction: if anything fails, nothing is changed.

### Which entry is used

The entry is searched with the provider's own settings: an equality filter on the provider's **Username LDAP
attribute** (e.g. `sAMAccountName`), under its **Users DN**, with its search scope and **User LDAP filter**. It is
an exact match: the user `test` never matches `test.a` or `a.test`, and wildcards in the username are escaped.

Linking is refused, with the reason, when:

- no entry, or more than one entry, has that username;
- the entry's username differs from the user's (servers with unusual matching rules);
- the entry is already linked to another Keycloak user;
- another user already has the entry's e-mail (and the realm does not allow duplicate e-mails);
- the user is already linked to a federation provider, or is a client's service account;
- the realm has no enabled LDAP provider, several (choose one), or the provider does not import users.

### Preview

Every way of linking offers a preview. It runs the same code as linking, records the result (the user before and
after, the entry found) and rolls the transaction back, so it shows exactly what linking would do and changes
nothing.

## Ways to use it

### Admin console: tab "LDAP relink"

On the user details page, next to Details, Credentials, ... (scroll the tab bar if it does not fit).

1. **Action** `preview`, **Save**: the result is shown as a message, e.g. "Preview, nothing changed. The user (ID
   ..., unchanged) would be linked to CN=...; e-mail: old → new; enabled: false → true." It is a red error message
   because the admin console treats it as "not saved": nothing was.
2. **Action** `attach`, **Confirm username**: type the user's username, **Save**. "Saved successfully" means the
   user is linked. If linking is refused, the error message says why and nothing changed. Reload the page to see the
   new values in the other tabs.

**LDAP provider** can stay empty when the realm has one LDAP provider; otherwise enter its name.

Requirements:

- The `declarative-ui` feature: `kc.sh start --features=declarative-ui` (or `KC_FEATURES=declarative-ui`), with
  `kc.sh build` for optimized starts. This feature is marked **experimental** by Keycloak. Without it the tab is
  simply not shown, the REST endpoint and the script keep working.
- The admin console saves the tab through the Components API, which requires **`manage-realm`** (realm
  administrators). Administrators with only `manage-users` use the REST endpoint or the script.

The admin console saves one component of type `UiTabProvider` (provider `ldap-relink`) per realm the first time;
its configuration is always empty. Each successful attach shows up in the admin events as an update of that
component and in the server log as `Linked user '...' (ID ...) ... to LDAP entry '...'`.

### REST

```
GET  /admin/realms/{realm}/ldap-relink/users/{user-id}[?provider=<name or ID>]          preview
POST /admin/realms/{realm}/ldap-relink/users/{user-id}/attach[?provider=<name or ID>]   link
```

Both need the right to manage that user: `realm-management` role `manage-users` (or realm admin, or the
fine-grained admin permission). Responses:

```json
{
  "applied": true,
  "userId": "0b3c...",
  "provider": { "id": "...", "name": "ldap" },
  "ldapEntry": { "dn": "CN=Jane Doe,OU=Staff,DC=example,DC=com", "uuid": "1c6e...", "username": "jdoe" },
  "before": { "username": "jdoe", "email": "jane.old@example.com", "enabled": false, "groups": [], ... },
  "after":  { "username": "jdoe", "email": "jane@example.com", "enabled": true, "federationLink": "ldap",
              "ldapId": "1c6e...", "ldapEntryDn": "CN=Jane Doe,...", "groups": ["/Staff"], ... },
  "problems": []
}
```

- preview: `200`, `applied` is `false`, `problems` lists what would prevent linking;
- attach: `200` when linked (also recorded as admin event `UPDATE` of `users/{user-id}`, with the response as
  representation if representations are included), `409` with the problems when not, nothing changed;
- `403` without the right to manage the user, `404` for an unknown user.

### Script

[`scripts/ldap-relink.sh`](scripts/ldap-relink.sh) (bash, curl, jq) calls the REST endpoint for a list of
usernames or user IDs. Without `--apply` it only previews.

```sh
cat > relink.conf <<'EOF'
KC_URL=https://keycloak.example.com
KC_REALM=myrealm
KC_CLIENT_ID=ldap-relink
KC_CLIENT_SECRET=...
EOF
chmod 600 relink.conf

./ldap-relink.sh --config relink.conf jdoe                         # preview one user
./ldap-relink.sh --config relink.conf --file returning.csv         # preview a list (first CSV column)
./ldap-relink.sh --config relink.conf --apply --report out.txt jdoe 0b3c...   # link
```

```
ldap-relink: realm myrealm, 1 user(s), attach

[LINKED] jdoe (0b3c...) -> CN=Jane Doe,OU=Staff,DC=example,DC=com (provider ldap)
       email: jane.old@example.com -> jane@example.com
       enabled: false -> true
```

Exit status `0` when every user is (or can be) linked, `1` when at least one is not, `2` for usage or login errors.
`--report FILE` appends the same output to a file, e.g. to mail it. Run `./ldap-relink.sh --help` for all options.

The client: confidential, **Service accounts roles** on, everything else off; in its **Service account roles**
assign `realm-management` → `manage-users`. `KC_CLIENT_REALM` is needed only when the client is in another realm
than the users.

## Set up

1. Install the jar (see [Install](../README.md#install)).
2. For the admin console tab, start Keycloak with `--features=declarative-ui`.
3. For the script, create the client described above.

Nothing else: the extension has no configuration, it uses the realm's LDAP provider settings.

## Recommended procedure for returning people

1. Do **not** delete the local placeholder when the person's new LDAP entry is created.
2. Until linked, the person cannot sign in: the username belongs to the disabled placeholder, so Keycloak does not
   import the new entry (the periodic sync reports it as failed: "already exists in Keycloak database but is not
   linked to federation provider").
3. Link: tab, REST or script. Preview first if in doubt.

Placeholders with a given ID can be created without touching the database: `POST /admin/realms/{realm}/partialImport`
with `{"ifResourceExists": "FAIL", "users": [{"id": "<old ID>", "username": "...", "enabled": false, ...}]}` keeps
the ID. Create it after the LDAP entry is gone: while the entry exists, the username lookup imports it.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| No "LDAP relink" tab | `declarative-ui` is not enabled, or the jar is not installed (no `KC-SERVICES0047 ... ldap-relink ... ui-tab` warning at start). |
| Tab: "Error could not save item! ... 403" or no Save | The admin has no `manage-realm`: use the REST endpoint or the script. |
| `No LDAP entry with <attr>=<username> under <DN>` | The entry does not exist yet, is outside the Users DN, or the User LDAP filter excludes it (e.g. a filter for enabled AD accounts). |
| `email ... already exists in Keycloak` | Another user has the new e-mail, often an earlier import of the same person. Delete or change that user, then link. |
| `already linked to the user federation provider` | Nothing to do, the user is an LDAP user already. |
| `several enabled LDAP providers` | Pass the provider: `?provider=<name>`, `--provider <name>`, or the tab's **LDAP provider** field. |
| Header still shows "Disabled" after attach in the tab | The page is not reloaded by the admin console: reload it. |
