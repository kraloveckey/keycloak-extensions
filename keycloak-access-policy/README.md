# keycloak-access-policy

Part of [keycloak-extensions](../README.md).

An authenticator that decides, from one JSON policy, which users may sign in to which clients of a realm. A user
gets in if they have one of the realm roles, client roles or groups the policy lists for that client.

Keycloak lets any user of a realm sign in to any client of it; restricting that is left to the application.
Keycloak's own building blocks ("Condition - user role" + "Deny access") check one role per condition, have no group
condition and need a separate flow bound to every client. This extension keeps all rules of a realm in one place.

## What it checks

- **Realm roles**, effective ones: assigned directly, through a composite role or through a group.
- **Client roles of any client**, not only of the client being signed in to, e.g. access to `hr-portal` for users
  with the `hr` role of `staff-app`.
- **Groups** by full path, `/Staff/HR`. Members of subgroups count: a member of `/Staff/HR` is a member of `/Staff`.
- **Clients** by exact client ID or by regular expression (entries starting with `^`), which also covers SAML
  clients whose client ID is a URL.

It runs on every sign-in to a client, also when the user already has an SSO session (see the flow layout below).

## Policy format

```json
{
  "unlistedClients": "allow",
  "rules": [
    {
      "clients": ["staff-portal"],
      "realmRoles": ["employee"],
      "clientRoles": { "staff-portal": ["user"], "hr-app": ["admin"] },
      "groups": ["/Staff"]
    },
    {
      "clients": ["^https://learning\\.example\\.com/.*$"],
      "groups": ["/Staff/Teachers", "/Students"]
    },
    {
      "clients": ["legacy-crm"]
    }
  ]
}
```

| Field             | Meaning                                                                                                   |
|-------------------|-----------------------------------------------------------------------------------------------------------|
| `unlistedClients` | `allow` (default) or `deny`: what happens with clients no rule applies to.                                  |
| `rules`           | List of rules.                                                                                            |
| `clients`         | Required. Client IDs the rule applies to. Entries starting with `^` are regular expressions matched against the whole client ID (escape dots: `\\.` in JSON). |
| `realmRoles`      | Realm role names.                                                                                         |
| `clientRoles`     | Object: client ID → list of role names of that client.                                                    |
| `groups`          | Group paths starting with `/`. Subgroup members count.                                                     |

How a sign-in to client `X` is decided:

1. Take all rules whose `clients` match `X`. If there are none, `unlistedClients` decides.
2. Otherwise the user gets in if **any** role or group of **any** of those rules matches (OR).
3. A rule without `realmRoles`, `clientRoles` and `groups` matches nobody, so it blocks its clients (`legacy-crm`
   above).

Unknown roles, clients and groups never match; they are not errors, so the policy can mention something that is
created later.

### Examples

Only employees may use the portal, everything else stays open:

```json
{ "rules": [ { "clients": ["staff-portal"], "realmRoles": ["employee"] } ] }
```

Allow-list mode: only the listed clients can be used at all. Every user has the realm's default role
`default-roles-<realm>`, so this keeps `account-console` open for everybody:

```json
{
  "unlistedClients": "deny",
  "rules": [
    { "clients": ["account-console"], "realmRoles": ["default-roles-myrealm"] },
    { "clients": ["staff-portal", "wiki"], "groups": ["/Staff"] }
  ]
}
```

Access to an application for holders of a role of another client:

```json
{ "rules": [ { "clients": ["reports"], "clientRoles": { "erp": ["accountant", "manager"] } } ] }
```

All SAML applications on one host by pattern, for two groups:

```json
{ "rules": [ { "clients": ["^https://apps\\.example\\.com/.*$"], "groups": ["/Staff", "/Contractors"] } ] }
```

## Setup

### 1. Flow layout

The check has to run **after** the user is known and **also** for users who already have an SSO session. In
Keycloak's default browser flow the steps are alternatives at the top level, and a REQUIRED step next to them
would disable them. So the alternatives go into a sub-flow and the policy follows it:

```
browser with access policy            (top-level flow)
├── Authenticate                      sub-flow, REQUIRED
│   ├── Cookie                        ALTERNATIVE
│   ├── Identity Provider Redirector  ALTERNATIVE     (if you use it)
│   └── Forms                         sub-flow, ALTERNATIVE
│       ├── Username Password Form    REQUIRED
│       └── ... OTP etc., as in your current browser flow
└── Access policy                     REQUIRED        <- this extension
```

In the admin console (realm you want to protect):

1. **Authentication** → **Create flow**: name `browser with access policy`, type *Basic flow*.
2. **Add sub-flow** `Authenticate` → set it to *Required*.
3. In `Authenticate`: **Add step** *Cookie* → *Alternative*. Add the other alternatives your current browser flow has
   (Identity Provider Redirector, Kerberos, Organization).
4. In `Authenticate`: **Add sub-flow** `Forms` → *Alternative*; inside it **Add step** *Username Password Form* →
   *Required*, plus the OTP / conditional steps of your current flow.
5. On the top level: **Add step** *Access policy* → *Required*. Click the gear icon, give the config an alias and
   paste the policy JSON. Optionally set an error message.
6. Flow **Action** menu → **Bind flow** → *Browser flow*.

Test with a second browser profile before logging out of the admin console. If you lock yourself out, the admin
console of the `master` realm is not affected by a policy in another realm.

### 2. Other ways in

- **Identity providers.** Sign-ins through an external IdP continue in the IdP's *first broker login* / *post
  login* flow. To apply the policy there too, create a flow that contains only *Access policy* (Required) and set
  it as **Post login flow** of every identity provider.
- **Password grant (direct access grants).** API clients that sign in with a password use the *direct grant*
  flow. Add *Access policy* (Required) to a copy of it and bind that as *Direct grant flow*; denied requests get
  `403` with the OAuth2 error `access_denied`. Or switch off *Direct access grants* for clients that do not need
  them.
- **Client-specific flow overrides** (client → Advanced → Authentication flow overrides) replace the realm's flow for
  that client; put *Access policy* into those flows too.

### 3. Check it

Sign in with a user that should be denied: Keycloak shows "Access denied" (or your message) and the realm's events
contain a `LOGIN_ERROR` with `error=access_denied` and `access_policy_reason`, e.g.
`none of the roles or groups required by rule(s) 1`. With debug logging
(`--log-level=INFO,io.github.kraloveckey:debug`) every decision is logged, including which rule, role or group
granted access.

## Invalid policy

Keycloak offers no hook to validate an authenticator's config when it is saved, so the policy is validated on
use. An invalid policy (broken JSON, unknown field such as `"client"` instead of `"clients"`, a group without the
leading `/`, a broken regular expression) **denies every sign-in** through the flow and logs the exact problem:

```
Access policy of realm 'demo' is invalid, denying access to 'wiki' for 'alice': the access policy is not valid JSON:
Unrecognized field "client" ...
```

Denying rather than ignoring the policy means a typo never opens every client. Keep a second admin session open
while editing the policy, and validate the JSON before pasting it (`jq . policy.json`).

## Limits

- The policy decides at sign-in. Users who are already signed in to a client keep their session until it ends,
  even if a role is removed; to cut access immediately, also sign the user out (Users → Sessions).
- It does not replace authorization inside applications; it only decides whether a user may get a token or SAML
  assertion for the client at all.

## License

[MIT](../LICENSE)
