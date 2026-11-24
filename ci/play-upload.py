#!/usr/bin/env python3
"""Uploads a signed app bundle to a Google Play track (internal testing by
default) with the Play Developer API, as the service account whose JSON key
is in PLAY_SERVICE_ACCOUNT_JSON. Standard library only, plus PyJWT and
cryptography for the service account's signed token.

  play-upload.py <bundle.aab> [track] [status]

The status is "completed" once the app has been reviewed for the track;
"draft" before (a new app's first releases must be drafts, finished in
Play Console)."""

import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import jwt

PACKAGE = "cc.ortus.app"
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"
UPLOAD = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}"


def token(account: dict) -> str:
    now = int(time.time())
    assertion = jwt.encode(
        {
            "iss": account["client_email"],
            "scope": "https://www.googleapis.com/auth/androidpublisher",
            "aud": account["token_uri"],
            "iat": now,
            "exp": now + 1800,
        },
        account["private_key"],
        algorithm="RS256",
    )
    body = urllib.parse.urlencode(
        {"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": assertion}
    ).encode()
    with urllib.request.urlopen(urllib.request.Request(account["token_uri"], data=body)) as r:
        return json.load(r)["access_token"]


def call(method: str, url: str, bearer: str, body=None, content_type="application/json") -> dict:
    data = body if isinstance(body, bytes) or body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Authorization", f"Bearer {bearer}")
    if data is not None:
        request.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(request, timeout=600) as r:
            text = r.read()
            return json.loads(text) if text else {}
    except urllib.error.HTTPError as e:
        sys.exit(f"play-upload: {method} {url.split('?')[0]}: {e.code} {e.read().decode()[:500]}")


def main() -> None:
    bundle = sys.argv[1]
    track = sys.argv[2] if len(sys.argv) > 2 else "internal"
    status = sys.argv[3] if len(sys.argv) > 3 else "completed"
    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "")
    if not raw:
        sys.exit("play-upload: PLAY_SERVICE_ACCOUNT_JSON is not set: see store/android/README.md")
    bearer = token(json.loads(raw))
    edit = call("POST", f"{API}/edits", bearer, {})["id"]
    with open(bundle, "rb") as f:
        uploaded = call(
            "POST",
            f"{UPLOAD}/edits/{edit}/bundles?uploadType=media",
            bearer,
            f.read(),
            "application/octet-stream",
        )
    code = uploaded["versionCode"]
    call(
        "PUT",
        f"{API}/edits/{edit}/tracks/{track}",
        bearer,
        {"track": track, "releases": [{"versionCodes": [str(code)], "status": status}]},
    )
    call("POST", f"{API}/edits/{edit}:commit", bearer)
    print(f"play-upload: version {code} on the {track} track ({status})")


if __name__ == "__main__":
    main()
