# Microsoft sign-in: Azure app registration

Minecraft accounts sign in through Microsoft. Every third-party launcher needs its **own** Azure
application (client ID) that **Mojang has approved** for the Minecraft API. Luncher never ships a
client ID; you create one once and give it to the launcher.

Everything else in Luncher (downloads, instances, mods, modpacks, themes) works without it.

## 1. Register the application

1. Sign in at <https://entra.microsoft.com> (or <https://portal.azure.com> → *Microsoft Entra ID*)
   with any Microsoft account. A free account is enough; no subscription is charged.
2. *Applications → App registrations → New registration*:
   - **Name**: e.g. `Luncher` (shown on Microsoft's consent screen).
   - **Supported account types**: *Personal Microsoft accounts only*.
   - **Redirect URI**: platform *Public client/native (mobile & desktop)*, URI `http://localhost`.
     (Luncher listens on a random local port; Microsoft ignores the port for `localhost`.)
   - *Register*.
3. Copy the **Application (client) ID** from the *Overview* page (a GUID).
4. *Authentication → Advanced settings → Allow public client flows* → **Yes** → *Save*.
   This enables the "Use a code" sign-in (device code flow) for machines without a browser.
5. Nothing else is needed: no client secret (Luncher is a public client and uses PKCE), and no
   API permissions to add — Luncher asks for `XboxLive.signin offline_access` at sign-in.

## 2. Ask Mojang for Minecraft API access

New client IDs are rejected by the Minecraft services until Mojang reviews them.

1. Fill in the form at <https://aka.ms/mce-reviewappid> with the client ID and a short
   description of the launcher.
2. Wait for approval (typically days, sometimes a few weeks). You will get an e-mail.

Before approval the Microsoft and Xbox steps succeed, but the final step fails and Luncher shows
*"Mojang has not approved this Azure app yet ("Invalid app registration")"*
(`auth.invalidAppRegistration`). That is expected — nothing is wrong with your account.

## 3. Give the client ID to Luncher

Any one of these, highest priority first:

| Where | When to use |
|-------|-------------|
| Environment variable `LAUNCHER_MSA_CLIENT_ID` at **run time** | testing, CI, portable setups |
| Environment variable `LAUNCHER_MSA_CLIENT_ID` at **build time** (`pnpm tauri build`) | your own distributed builds |
| *Settings → Accounts → Azure client ID* (or the notice on the Accounts page) | personal use |

The client ID is an identifier, not a secret, but keep secrets (none are needed here) out of the
repository — see `.env.example`.

## 4. Sign in

*Accounts → Sign in with Microsoft* opens the system browser; after signing in, return to
Luncher. *Use a code* shows a code and the Microsoft page to enter it on, from any device.
Tokens are stored only in the operating system's credential store (Windows Credential Manager,
macOS Keychain, Secret Service/GNOME Keyring on Linux) and refreshed silently.

## Error messages

| Message (code) | Meaning | What to do |
|----------------|---------|------------|
| `auth.clientIdMissing` | No client ID configured | Step 3 |
| `auth.invalidAppRegistration` | Mojang has not approved this client ID (yet) | Step 2, wait |
| `auth.noXboxAccount` | The Microsoft account has no Xbox profile | Sign in once at <https://www.xbox.com> or the official launcher |
| `auth.childAccount` | Under-18 account not in a Microsoft family | An adult adds it at <https://account.microsoft.com/family> |
| `auth.adultVerification` | South Korea: adult verification required | Verify at xbox.com |
| `auth.countryUnavailable` | Xbox Live is not available in the account's country | — |
| `auth.xboxBanned` | The Xbox account is banned | — |
| `auth.noLicense` / `auth.noProfile` | No Minecraft: Java Edition licence, or no profile yet (Game Pass users: start the official launcher once) | Buy/redeem the game or create the profile |
| `auth.suspended` | Minecraft account suspended | Contact Mojang support |
| `auth.sessionExpired` | Refresh token expired or revoked | Sign in again |
| `auth.declined` / `auth.expired` | Code sign-in cancelled or timed out | Retry |

## Offline accounts

Offline accounts are only available after a licensed Microsoft account has been added (the
launcher must not help bypass licensing). For development, debug builds accept
`LAUNCHER_DEV_OFFLINE=1`; release builds ignore it.
