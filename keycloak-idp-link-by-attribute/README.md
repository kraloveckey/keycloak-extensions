# keycloak-idp-link-by-attribute

Part of [keycloak-extensions](../README.md).

A first-broker-login step, **Detect existing broker user by attribute**, for realms where users already exist and
sign in through an identity provider (Google, another OIDC provider, SAML) for the first time. It finds the existing
user whose **custom attribute** equals the e-mail sent by the identity provider, so that Keycloak links the provider
account to that user instead of creating a new one.

Keycloak's own *Detect existing broker user* compares the provider's e-mail with the user's `email` and `username`
only. When the address that matters is stored elsewhere, e.g. `workEmail` holds the Google Workspace address while
`email` holds another one, that step cannot find the user.

## How it works

```
Sign-in through the identity provider
├── account already linked (provider's user id known)  -> signed in, this step does not run
└── first sign-in with this provider account           -> first login flow:
    1. Detect existing broker user by attribute   finds the user, puts it into the EXISTING_USER_INFO note
    2. Automatically set existing user            (Keycloak) signs in as that user
    -> Keycloak links the provider account to the user when the flow completes
```

The step writes nothing itself. It lower-cases the provider's e-mail, searches users whose attribute equals it
exactly, and decides:

| Found | Result | Page shown (message key) | Event error / log |
|-------|--------|--------------------------|-------------------|
| 1 user, enabled, not linked to this provider | linked | none | none |
| 0 users | refused, 403 | `idpAttrUserNotFound` | `user_not_found`, INFO with the e-mail |
| 2 or more | refused, 409 | `idpAttrConflict` | `duplicate_attribute_value`, WARN with e-mail, attribute and all user ids |
| 1, but disabled | refused, 403 | `idpAttrUserDisabled` | `user_disabled` |
| 1, already linked to another account of this provider | refused, 409 | `idpAttrAlreadyLinked` | `federated_identity_account_exists` with `idp_user_id` / `linked_idp_user_id`, WARN with both provider account ids |
| provider says `email_verified=false` | refused, 403 | `idpAttrEmailNotVerified` | `email_not_verified`, WARN |
| no attribute name configured, or no e-mail from the provider | refused, 500 | Keycloak's "An internal server error has occurred" | `invalid_config`, ERROR |

- **Already linked.** Without this check Keycloak would try to create a second link for the same user, hit the
  primary key of `federated_identity` and show only "Unexpected error when authenticating with identity provider".
- **Unverified e-mail.** If the provider's ID token contains `email_verified=false`, the address is refused, so an
  account with an unverified address elsewhere cannot take over a user. Providers that send no such claim (SAML,
  some OAuth2 providers) are not affected. Google Workspace addresses are always verified. The check can be switched
  off (*Reject unverified e-mail*).
- **Later sign-ins** find the user through the link, the attribute is not looked at again. If the attribute value
  changes, the old link stays; remove it in Users → user → Identity provider links → Unlink.

## Setup

### 1. Install

Copy `keycloak-idp-link-by-attribute-<version>-kc<keycloak>.jar` to `/opt/keycloak/providers/`, run `kc.sh build`
for optimized starts, restart. **Authentication → Add step** then lists *Detect existing broker user by attribute*.

### 2. First login flow

1. **Authentication → Create flow**: name e.g. `IdP first login by attribute`, type *Basic flow*.
2. **Add step** → *Detect existing broker user by attribute* → *Required*. Gear icon: an alias, **Attribute name**
   (e.g. `workEmail`), *Reject unverified e-mail* (on).
3. **Add step** → *Automatically set existing user* → *Required*. It must come second.

The flow contains nothing else: users that are not found are refused, no account is created.

### 3. Identity provider

**Identity providers → your provider (e.g. `google`) → Advanced settings**:

- **First login flow override**: the flow from step 2.
- **Post login flow**: leave as it is. It runs after the first login flow as well, so e.g. a
  [keycloak-access-policy](../keycloak-access-policy/README.md) there still decides access for newly linked users.
- **Google**: set **Hosted domain** to your Workspace domain (`example.com`). Without it any Google account can reach
  the flow; with it Keycloak accepts only accounts of that domain.

### 4. The attribute

**Realm settings → User profile**: the attribute must exist there (or unmanaged attributes must be enabled). Give
**edit** permission to *admin* only: a user who can edit the attribute can write somebody else's address into it
and take over that person's first sign-in.

Values must be **lower case**: the e-mail from the provider is lower-cased and compared exactly, so
`Alice@Example.com` in the attribute is not found.

Keycloak does not enforce unique attribute values. Check before going live and from time to time (PostgreSQL;
replace the attribute and realm names):

```sql
-- the same value on several users (these users get "account conflict")
SELECT ua.value, count(*) AS users, string_agg(u.username, ', ') AS usernames
FROM user_attribute ua
JOIN user_entity u ON u.id = ua.user_id
WHERE ua.name = 'workEmail'
  AND u.realm_id = (SELECT id FROM realm WHERE name = 'myrealm')
GROUP BY ua.value
HAVING count(*) > 1;

-- values that are not lower case (these users are "not found")
SELECT u.username, ua.value
FROM user_attribute ua
JOIN user_entity u ON u.id = ua.user_id
WHERE ua.name = 'workEmail'
  AND u.realm_id = (SELECT id FROM realm WHERE name = 'myrealm')
  AND ua.value <> lower(ua.value);
```

### 5. Messages

The messages ship with the jar in English and Ukrainian (`src/main/resources/theme-resources/messages/`), chosen by
the user's language. `{0}` is the e-mail from the provider.

To change a text, e.g. to add a support contact: **Realm settings → Localization → Realm overrides**, per language,
key as in the table above. A login theme's `messages_<lang>.properties` works too; both take precedence over the jar.

In these texts a plain apostrophe `'` is a special character (Java MessageFormat): `прив'язаний` loses the
apostrophe and can break the `{0}` after it. Use the typographic `’` (as the shipped texts do) or write `''`.

## "Already linked" for a user who is linked

This step runs only when Keycloak found **no** link for the provider account that is signing in. So when a user
who has a link gets `idpAttrAlreadyLinked`, a **different** provider account (another id) came with the address in
that user's attribute. The WARN line and the event show both ids:

```
User 'jdoe' (..., workEmail=jdoe@example.com) is already linked to 'google' account 1137...774 (jdoe@example.com);
refusing sign-in of 'google' account 1098...231 (jdoe@example.com). ...
```

- **Same address, new id:** the provider account was deleted and re-created (Google Workspace gives a re-created
  account a new id). Unlink the old account (Users → user → Identity provider links → Unlink account) and let the
  user sign in again; the new account gets linked.
- **Different person:** the attribute holds a wrong address. Fix the attribute; do **not** unlink, that would hand
  the user to the other account.

The events can be queried in the database as well:

```sql
SELECT to_timestamp(event_time / 1000) AS time, user_id, details_json
FROM event_entity
WHERE realm_id = (SELECT id FROM realm WHERE name = 'myrealm')
  AND error = 'federated_identity_account_exists'
ORDER BY event_time DESC LIMIT 10;
```

## Check it

On a test user without a link to the provider (remove the link between checks: Users → user → Identity provider
links → Unlink):

| # | Preparation | Expected |
|---|-------------|----------|
| 1 | one user's attribute equals the provider e-mail | signed in; the user has the provider in Identity provider links |
| 2 | sign in again after 1 | signed in through the link; changing the attribute makes no difference |
| 3 | nobody has the value | page `idpAttrUserNotFound`, no link |
| 4 | two users have the value | page `idpAttrConflict`, WARN with both ids, no link |
| 5 | the user is linked to another account of the provider | page `idpAttrAlreadyLinked`, the existing link unchanged |
| 6 | the user is disabled | page `idpAttrUserDisabled` |
| 7 | the value is upper case | page `idpAttrUserNotFound` (values must be lower case) |
| 8 | no *Attribute name* in the step | "An internal server error has occurred", ERROR in the log |
| 9 | after linking, a user without the required roles opens a client guarded by the post login flow | denied by the post login flow |

All of them were run against Keycloak 26.7.4 and 26.8.0 with a second Keycloak realm acting as the OIDC provider.

Debug logging (`--log-level=INFO,io.github.kraloveckey:debug`) also logs every successful match.

## License

[MIT](../LICENSE)
