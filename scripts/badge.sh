#!/usr/bin/env bash
# Give a player a badge, or take it away. Badges show next to names in the game (Aerion mod).
#   scripts/badge.sh <nick> <creator|bugHunter|tester|contributor>
#   scripts/badge.sh <nick> remove
# Edits data/badges.json; commit and push it, and launchers pick it up within an hour.
set -euo pipefail
cd "$(dirname "$0")/.."
[ $# -eq 2 ] || { sed -n 3,5p "$0"; exit 1; }
python3 - "$1" "$2" <<'PY'
import json, sys, urllib.request
name, badge = sys.argv[1], sys.argv[2]
known = ["creator", "bugHunter", "tester", "contributor"]
if badge != "remove" and badge not in known:
    sys.exit(f"Unknown badge '{badge}'. Use one of: {', '.join(known)} or remove")
try:
    with urllib.request.urlopen(f"https://api.mojang.com/users/profiles/minecraft/{name}") as r:
        profile = json.load(r) if r.status == 200 else None
except Exception:
    profile = None
if not profile:
    sys.exit(f"No Minecraft account named '{name}'")
data = json.load(open("data/badges.json"))
players = [p for p in data["players"] if p["uuid"] != profile["id"]]
if badge != "remove":
    players.append({"name": profile["name"], "uuid": profile["id"], "badge": badge})
data["players"] = sorted(players, key=lambda p: p["name"].lower())
with open("data/badges.json", "w") as f:
    f.write(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
print(f"{profile['name']}: {badge}")
PY
