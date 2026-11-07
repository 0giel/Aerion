# Mojang's review of the Azure app

Mojang reviews each launcher's Azure client ID before it may use the Minecraft API. Aerion's form
went in on 2026-10-31 (client ID (kept out of the repository), site
<https://github.com/0giel/Aerion>). There is no public status page and no published time limit, so
this is how to tell where things stand and what to do next. Setup details: [AZURE_SETUP.md](AZURE_SETUP.md).

## How to tell without an e-mail

Sign in with a Microsoft account that owns Minecraft, in a normal Aerion build:

| Result | Meaning |
|---|---|
| "Mojang has not approved this Azure app yet" (`auth.invalidAppRegistration`) | Not approved yet |
| Signed in | Approved, even if no e-mail arrived |
| Any other error | See the table in AZURE_SETUP.md |

Trying again does no harm.

## If approved

1. Sign in, check the profile, skin and cape, and launch a game with the account.
2. Check that offline accounts unlock afterwards.
3. Check the same on real Windows.
4. Check that the release was built with the client ID (`release.sh` refuses otherwise).
5. Build a beta for testers, or publish the release (see below).
6. Remove the sign-in notice from the GitHub README's Status section.

## If rejected

1. Read the reason; fix what it names (site, description, screenshots) and send the form again.
2. If the reason is unclear, ask them what is missing before resubmitting.
3. Until it passes, the launcher can't start the game with an account, so don't advertise it as
   ready. Testing goes on with the developer builds.

Don't borrow another launcher's client ID, and don't add a way around the ownership check.

## If they ask for more information

Reply from the same address with what they ask for. Useful to have ready: the client ID, the site,
what the launcher does, that sign-in uses Microsoft's OAuth with `XboxLive.signin offline_access`,
that tokens stay in the OS keyring and nothing goes to any Aerion server. The source code is private
(owner's decision): if they ask for it, that is the owner's call.

## If nothing comes

Keep testing the sign-in now and then and check spam. If it has been some weeks, sending the form
again with the same client ID is a reasonable next step (our suggestion, not an official procedure).

## If the client ID ever changes

A new Azure app means a new review. Put the new ID in `~/.config/aerion-signing/msa-client-id` and
rebuild; installed copies get it with the next update, unless someone set their own in settings.

## Testers before approval

- Normal installers can't sign in, so a tester can't play with them.
- `scripts/export-dev.sh` builds are for the owner only. They can skip the ownership check, so they
  aren't handed out.
- A tester who owns the game can try the mod's jar in another launcher (`src-tauri/resources/`).
