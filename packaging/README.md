# Packaging drafts

Drafts for other install channels. Nothing here is submitted; each needs the maintainer's account.

- `winget/` — manifests for [winget-pkgs](https://github.com/microsoft/winget-pkgs) (`0giel.Aerion`).
  Check with `winget validate` and `winget install --manifest <dir>` on Windows, then open a PR there.
  Each release needs a new folder with the new version, URL and SHA256 (GitHub shows the digest of
  every release file).
- `aur/PKGBUILD` — `aerion-bin` for Arch Linux, from the release `.deb`. Check with `makepkg -si`
  and `namcap`, generate `.SRCINFO` with `makepkg --printsrcinfo > .SRCINFO`, push to
  `ssh://aur@aur.archlinux.org/aerion-bin.git`.
- Flathub — not drafted: Flathub builds from source with every Cargo and pnpm dependency listed
  offline (`flatpak-cargo-generator`, `flatpak-node-generator`), and the launcher's self-update
  must be off there. A larger job; worth it once the other channels work.
