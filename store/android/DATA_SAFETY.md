# Data safety answers (Play Console → App content → Data safety)

The app has no servers of its own and no third-party SDKs (no analytics,
no ads, no crash reporting). It talks only to the Ortus installation of
the person's organisation, which that organisation runs. Under Google's
definitions, data sent to a server the organisation controls on the
user's behalf is still "collected" by the app, so the answers below
declare it, as "processed" for the app's function.

| Question | Answer |
|---|---|
| Does the app collect or share user data? | Yes, collects. Shares: no. |
| Is all data encrypted in transit? | Yes (HTTPS only; plain HTTP only to a test server on the device's own machine in debug builds). |
| Can users ask for their data to be deleted? | Yes: their organisation's administrator removes the account and everything of it (Admin → People → Remove person). |

## Data types

| Type | Collected | Why | Optional |
|---|---|---|---|
| Personal info: name, email address | Yes | App functionality, account management | No |
| Messages: emails, in-app messages (chat) | Yes | App functionality | No |
| Photos and videos (profile photo; camera only for scanning codes, never stored) | Yes (profile photo) | App functionality | Yes |
| Files and docs | Yes | App functionality | Yes |
| Calendar events | Yes | App functionality | Yes |
| App activity: in-app actions (audit log of sign-ins and admin actions) | Yes | Security, fraud prevention | No |
| Device or other IDs: none (the phone's key is a passkey, not an ID) | No | | |
| Location, contacts, health, financial info, web history | No | | |

The camera is used only to scan sign-in and invitation codes; frames are
read on the phone and never sent. The phone's key (a passkey in the
Android Keystore, guarded by the screen lock) never leaves the phone.
