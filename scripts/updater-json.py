#!/usr/bin/env python3
"""Write the release's latest.json (the file installed launchers check for updates) from the
signatures the builds attached to it, so it always lists every platform, whichever build
finished first.

    updater-json.py <release id> [--dry-run]

Needs GITHUB_TOKEN and GITHUB_REPOSITORY (owner/name). With --dry-run it prints the file instead
of replacing the release's latest.json.
"""

import json
import os
import sys
import urllib.request
from datetime import datetime, timezone

# Updater platform key -> the file it installs (by suffix).
PLATFORMS = {
    "linux-x86_64": "_amd64.AppImage",
    "linux-x86_64-appimage": "_amd64.AppImage",
    "linux-x86_64-deb": "_amd64.deb",
    "windows-x86_64": "_x64-setup.exe",
    "windows-x86_64-nsis": "_x64-setup.exe",
}
NOTES = "What's new: see the launcher's changelog. / Co nowego: lista zmian w launcherze."


def api(url, accept="application/vnd.github+json", data=None, method=None, ctype=None):
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + os.environ["GITHUB_TOKEN"])
    req.add_header("Accept", accept)
    if ctype:
        req.add_header("Content-Type", ctype)
    with urllib.request.urlopen(req) as r:
        return r.read()


def main():
    release_id = sys.argv[1]
    dry = "--dry-run" in sys.argv
    repo = os.environ["GITHUB_REPOSITORY"]
    base = f"https://api.github.com/repos/{repo}"
    release = json.loads(api(f"{base}/releases/{release_id}"))
    tag = release["tag_name"]
    version = tag.removeprefix("v")
    assets = {a["name"]: a for a in release["assets"]}

    platforms = {}
    for key, suffix in PLATFORMS.items():
        name = f"Aerion_{version}{suffix}"
        sig = assets.get(name + ".sig")
        if name not in assets or sig is None:
            sys.exit(f"missing {name} or its .sig in the release: not writing latest.json")
        signature = api(
            f"{base}/releases/assets/{sig['id']}", accept="application/octet-stream"
        ).decode().strip()
        platforms[key] = {
            "signature": signature,
            "url": f"https://github.com/{repo}/releases/download/{tag}/{name}",
        }

    doc = {
        "version": version,
        "notes": NOTES,
        "pub_date": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z"),
        "platforms": platforms,
    }
    body = json.dumps(doc, indent=2).encode()
    if dry:
        print(body.decode())
        return
    if "latest.json" in assets:
        api(f"{base}/releases/assets/{assets['latest.json']['id']}", method="DELETE")
    upload = release["upload_url"].split("{")[0]
    api(f"{upload}?name=latest.json", data=body, method="POST", ctype="application/json")
    print(f"latest.json written for {version}: {', '.join(platforms)}")


if __name__ == "__main__":
    main()
