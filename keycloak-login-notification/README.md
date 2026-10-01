# keycloak-login-notification

Part of [keycloak-extensions](../README.md).

An authenticator that e-mails the user when they sign in

- **after a long absence**: the previous sign-in was longer ago than a configured period (30 days by default), and/or
- **from a new IP address**: one that is not among the addresses the account used recently (off by default).

## Why

Keycloak itself never tells users about sign-ins. If a password leaks, the owner usually learns about it only when
something visibly breaks. An e-mail like "New sign-in to your account: time, IP address, application, browser" lets
the real owner notice a sign-in that was not theirs and change the password. Long-unused accounts are the typical
target, hence the inactivity check; the new-address check catches sign-ins from unusual places.

The authenticator never blocks or fails a sign-in: no SMTP server, no e-mail address, a mail or storage error are
logged and the sign-in continues.

## What the e-mail contains

Subject: *New sign-in to your &lt;realm&gt; account*. Body: the reason (previous sign-in N days ago / new IP address),
time, IP address, application (client name), browser (User-Agent) and a link to the account console to change the
password and review sessions.

It uses the realm's e-mail theme and the user's language. English and Ukrainian texts ship with the extension;
Keycloak picks by the user's `locale` attribute, falling back to the realm's default language.

## Setup

### 1. Prerequisites

- **Realm settings → Email**: an SMTP server, and *Test connection* works.
- Users have an e-mail address, by default a **verified** one (see *Verified e-mail only*). For LDAP users enable
  *Trust Email* in the LDAP provider, otherwise their addresses count as unverified.

### 2. Add it to the browser flow

Put the step **inside the forms sub-flow, after the credential steps**. There it runs when the user actually types
credentials; SSO sign-ins through the session cookie do not trigger it, which is what you want (one e-mail per
real sign-in, not per application opened).

```
browser (copy)
├── Cookie                          ALTERNATIVE
├── Identity Provider Redirector    ALTERNATIVE
└── forms                           ALTERNATIVE
    ├── Username Password Form      REQUIRED
    ├── Conditional OTP             CONDITIONAL    (if you use it)
    └── Login notification e-mail   REQUIRED       <- this extension
```

In the admin console:

1. **Authentication** → flow **browser** → **Action** → **Duplicate**, name it e.g. `browser with notifications`
   (built-in flows cannot be edited).
2. In the copy, on the `... forms` sub-flow: **+** → **Add step** → *Login notification e-mail* → *Required*. Move
   it below the credential and OTP steps.
3. Click its gear icon and set the options (below); an alias is required, e.g. `login-notification`.
4. **Action** → **Bind flow** → *Browser flow*.

Sign-ins through an external identity provider do not pass the forms sub-flow; add the step to a flow bound as the
identity provider's **Post login flow** if those should be covered too.

If you also use [keycloak-access-policy](../keycloak-access-policy/README.md), both fit in one flow: the
notification in the forms sub-flow, the access policy after the `Authenticate` sub-flow.

### 3. Options

| Option                     | Default | Meaning                                                                                   |
|----------------------------|---------|-------------------------------------------------------------------------------------------|
| Notify after inactivity    | `P30D`  | ISO-8601 duration: `P30D` = 30 days, `P7D`, `PT12H` = 12 hours. Empty switches the check off. |
| Notify on new IP address   | off     | E-mail when the address is not among the remembered recent ones.                           |
| Remembered IP addresses    | `5`     | How many recent addresses are kept per user for that check.                                 |
| Verified e-mail only       | on      | Send only to addresses marked as verified.                                                  |

The first sign-in after the step is added only starts the history, so switching it on does not e-mail every user.
An invalid option value falls back to its default and is logged.

### 4. Check it

Set *Notify after inactivity* to `PT1M`, sign in, wait a minute, sign in again: the mail arrives and the log shows

```
Login notification sent to 'alice' (realm 'demo', ip 10.0.0.1, inactivity true, new ip false)
```

Then set the period back.

## Where the history is stored

Two user attributes: `login-notification.last-login` (epoch seconds) and `login-notification.recent-ips`
(comma-separated, most recent first). They are always written to Keycloak's own database:

- local users and **imported LDAP users**: on the user's local record;
- **LDAP users without import**: in Keycloak's federated user storage.

They are never written to LDAP, so this works with LDAP in **READ_ONLY** mode (tested with and without import).
Writing them through the normal user API would fail there with "Federated storage is not writable" and break the
sign-in.

The attributes are not part of the user profile. To see them in the admin console, set **Realm settings → User
profile → Unmanaged attributes** to *Admin can view*. Deleting them resets the history of that user.

IP addresses are personal data; the extension keeps only the configured number of recent ones per user.

## Changing the texts or the design

The templates are `login-notification.ftl` (`html/` and `text/`) and the messages start with `loginNotification`
(all keys, with their placeholders, are in `src/main/resources/theme-resources/messages/messages_en.properties`).

Texts only, without a theme: **Realm settings → Localization → Realm overrides**, pick the language and add the key,
e.g. `loginNotificationSubject` = `Sign-in to {0}`.

Texts and layout in your own e-mail theme:

- texts: add the keys to `messages/messages_<lang>.properties` of the theme;
- layout: put your own `html/login-notification.ftl` / `text/login-notification.ftl` into the theme.

A theme's files take precedence over the ones in the jar.

## Troubleshooting

| Symptom                                   | Cause and fix                                                                                   |
|-------------------------------------------|-------------------------------------------------------------------------------------------------|
| No e-mail, log: `has no SMTP server configured` | Configure Realm settings → Email.                                                        |
| No e-mail, debug log: `not verified`      | The address is unverified: verify it, enable *Trust Email* for LDAP, or switch off *Verified e-mail only*. |
| No e-mail after SSO sign-ins              | Expected: the step is in the forms sub-flow and runs only when credentials are entered.        |
| E-mail for every sign-in from the office  | Behind a proxy all users may seem to come from one address: configure `proxy-headers` so Keycloak sees client IPs. |
| `Login notification failed ...` warning   | Mail or storage error; the sign-in went through. The stack trace in the log shows the cause.   |

Debug logging: `--log-level=INFO,io.github.kraloveckey:debug`.

## License

[MIT](../LICENSE)
