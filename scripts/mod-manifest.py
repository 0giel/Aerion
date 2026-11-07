#!/usr/bin/env python3
"""Write mod-latest.json for a mod release: the signed jars in DIR (aerion-ui-<mc>.jar and
their .sig files from `tauri signer sign`), their checksums and download URLs.
Used by scripts/release-mod.sh and .github/workflows/release-mod.yml.

Usage: mod-manifest.py DIR VERSION MIN_LAUNCHER NOTES
"""
import hashlib
import json
import pathlib
import sys

out, version, min_launcher, notes = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4]
base = f"https://github.com/0giel/Aerion/releases/download/mod-v{version}/"
jars = {}
for jar in sorted(out.glob("aerion-ui-*.jar")):
    game = jar.stem.removeprefix("aerion-ui-")
    jars[game] = {
        "url": base + jar.name,
        "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
        "signature": (out / f"{jar.name}.sig").read_text().strip(),
    }
if not jars:
    sys.exit(f"no jars in {out}")
manifest = {"version": version, "minLauncher": min_launcher, "notes": notes, "jars": jars}
pathlib.Path("data/mod-latest.json").write_text(json.dumps(manifest, indent=2) + "\n")
print(f"data/mod-latest.json: {version}, {', '.join(jars)}")
