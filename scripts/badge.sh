#!/usr/bin/env bash
# Give a player a badge, or take it away. Badges show next to names in the game (Aerion mod).
#   scripts/badge.sh <nick> <creator|bugHunter|tester|contributor> [more badges...]
#   scripts/badge.sh <nick> remove
# With several badges the first is the one others see; the player can pick which of theirs to show
# for themselves in the mod (Game features → Badges).
# Edits data/badges.json; commit and push it, and launchers pick it up within about five minutes.
set -euo pipefail
cd "$(dirname "$0")/.."
[ $# -ge 2 ] || { sed -n 3,5p "$0"; exit 1; }
python3 - "$@" <<'PY'
import json, sys, urllib.request
name, wanted = sys.argv[1], sys.argv[2:]
known = ["creator", "bugHunter", "tester", "contributor"]
remove = wanted == ["remove"]
bad = [b for b in wanted if b not in known]
if not remove and bad:
    sys.exit(f"Unknown badge '{bad[0]}'. Use one of: {', '.join(known)} or remove")
try:
    with urllib.request.urlopen(f"https://api.mojang.com/users/profiles/minecraft/{name}") as r:
        profile = json.load(r) if r.status == 200 else None
except Exception:
    profile = None
if not profile:
    sys.exit(f"No Minecraft account named '{name}'")
data = json.load(open("data/badges.json"))
players = [p for p in data["players"] if p["uuid"] != profile["id"]]
if not remove:
    entry = {"name": profile["name"], "uuid": profile["id"]}
    if len(wanted) == 1:
        entry["badge"] = wanted[0]
    else:
        entry["badges"] = list(dict.fromkeys(wanted))
    players.append(entry)
data["players"] = sorted(players, key=lambda p: p["name"].lower())
with open("data/badges.json", "w") as f:
    f.write(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
print(f"{profile['name']}: {', '.join(wanted)}")
PY
