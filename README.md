# Aerion

**Aerion** is a free desktop launcher for **Minecraft: Java Edition** (Windows and Linux), built
as a personal project. It opens in under half a second, uses about 180 MB of RAM and can be themed
to look the way you like.

> **Unofficial product.** Aerion is not approved by or associated with Mojang or Microsoft.
> "Minecraft" is a trademark of Mojang Synergies AB.

![Home screen](screenshots/home.png)

## Features

- **Microsoft account sign-in** for players who own the game (browser sign-in with PKCE, or a
  code), with silent token refresh.
- **Instances** — separate game folders per version and mod setup, each with its own icon
  (Minecraft item and block textures, or your own picture) and **favourites** pinned on top.
- **Vanilla and mod loaders** — Fabric, Quilt, Forge and NeoForge, installed automatically
  together with the right Java runtime from Mojang.
- **Mods, resource packs, shaders and modpacks** from Modrinth (and CurseForge with your own API
  key), with dependency resolution, updates and undo.
- **Skins** — a local skin library with a 3D preview; wear a skin or choose a cape on your own
  account through the official Minecraft services API. The player model on the home screen
  follows your cursor.
- **Themes**, crash analysis, game console, desktop shortcuts. Polish and English interface.

![Instances with icons and favourites](screenshots/instances.png)

![Choosing an instance icon](screenshots/picker.png)

![Mod browser](screenshots/mods.png)

![Skins](screenshots/skins.png)

## Accounts, privacy and security

- Sign-in uses Microsoft's official OAuth flow (scopes `XboxLive.signin offline_access`) and the
  official Minecraft services. Aerion verifies game ownership and **does not offer any way to
  bypass licensing**; offline play is only available after a licensed account was added.
- There is **no Aerion server**: tokens are kept only in the operating system's credential store
  (Windows Credential Manager, macOS Keychain, Secret Service on Linux), never in plain files,
  and are never sent anywhere except Microsoft and Mojang.
- All game files, including the textures used for icons, are downloaded from Mojang's servers
  at run time; no Minecraft assets are redistributed.

## Status

In active development by a single developer. The source code is not public at this time.
