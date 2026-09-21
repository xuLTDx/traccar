#!/usr/bin/env python3
"""
Bootstrap a fresh Traccar database with this deployment's users, device,
business addresses, and odometer calibration anchor. Run AFTER Traccar is
up and responding (02_configure_traccar.sh does that).

Python stdlib only, no dependencies. Prompts for every password/value it
needs - nothing is hardcoded, safe to commit.
"""
import json
import urllib.request
import urllib.parse
import http.cookiejar
import getpass

BASE = "http://localhost:8082"

DEVICE_UNIQUE_ID = "ZKUCA42T"
DEVICE_NAME = "VW Passat RK859CL"

BUSINESS_ADDRESSES = [
    {
        "name": ".:uLTD:.",
        "description": "sidlo firmy",
        "address": "1752/25 Karola Frantiska Palmu, Ruzomberok, Zilinsky kraj, SK",
        "latitude": 49.079420141240234,
        "longitude": 19.28690990205098,
        "radius": 150.0,
    },
    {
        "name": ".:uLTD:. pobocka",
        "description": "firemna pobocka na Bielom Potoku",
        "address": "Korytnicka 7353/1, 034 03 Ruzomberok-Biely Potok, Zilinsky kraj, SK",
        "latitude": 49.03359321811118,
        "longitude": 19.296640692987808,
        "radius": 300.0,
    },
]


def post(opener, path, obj):
    data = json.dumps(obj).encode()
    req = urllib.request.Request(f"{BASE}{path}", data=data, method="POST",
                                  headers={"Content-Type": "application/json"})
    body = opener.open(req).read()
    # Some endpoints (e.g. /api/permissions) return 204 No Content on
    # success - no JSON body to parse. Return None for those instead of
    # crashing on an empty response.
    return json.loads(body) if body else None


def get(opener, path):
    req = urllib.request.Request(f"{BASE}{path}", headers={"Accept": "application/json"})
    return json.loads(opener.open(req).read())


def new_opener():
    cj = http.cookiejar.CookieJar()
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))


def login(opener, email, password):
    data = urllib.parse.urlencode({"email": email, "password": password}).encode()
    opener.open(urllib.request.Request(f"{BASE}/api/session", data=data, method="POST")).read()


def main():
    print("=== 1. Primary personal account (becomes admin - first user ever) ===")
    admin_email = input("Your email: ").strip()
    admin_password = getpass.getpass("Password for this account: ")
    admin_user = post(new_opener(), "/api/users",
                       {"name": admin_email, "email": admin_email, "password": admin_password})
    print(f"Created: {admin_user['email']} (id={admin_user['id']}, administrator={admin_user.get('administrator')})")

    admin_opener = new_opener()
    login(admin_opener, admin_email, admin_password)

    print("\n=== 2. ota@ultd.sk (readonly, for ota_push_watcher.py) ===")
    ota_password = getpass.getpass(
        "Password (must match /opt/freematics-ota/traccar_credentials.json on the server): ")
    ota_user = post(admin_opener, "/api/users", {
        "name": "ota@ultd.sk", "email": "ota@ultd.sk", "password": ota_password,
        "readonly": True,
    })
    print(f"Created: {ota_user['email']} (id={ota_user['id']}, readonly={ota_user.get('readonly')})")

    print("\n=== 3. assist@ultd.sk (write access, not full admin, for ad-hoc maintenance) ===")
    assist_password = getpass.getpass("Password: ")
    assist_user = post(admin_opener, "/api/users", {
        "name": "assist@ultd.sk", "email": "assist@ultd.sk", "password": assist_password,
        "readonly": False,
    })
    print(f"Created: {assist_user['email']} (id={assist_user['id']}, readonly={assist_user.get('readonly')})")

    print(f"\n=== 4. Device: {DEVICE_NAME} ({DEVICE_UNIQUE_ID}) ===")
    device = post(admin_opener, "/api/devices", {
        "name": DEVICE_NAME, "uniqueId": DEVICE_UNIQUE_ID,
    })
    print(f"Created device id={device['id']}")

    for user in (admin_user, ota_user, assist_user):
        post(admin_opener, "/api/permissions", {"userId": user["id"], "deviceId": device["id"]})
        print(f"Shared device with {user['email']}")

    print("\n=== 5. Business addresses ===")
    for addr in BUSINESS_ADDRESSES:
        created = post(admin_opener, "/api/businessaddresses", addr)
        print(f"Created business address: {created['name']} (id={created['id']})")

    print("\n=== 6. Odometer calibration anchor ===")
    real_km = float(input("Current REAL odometer reading right now, in km (from the dashboard): "))
    factor = input("Correction factor to carry forward [default 1.1222]: ").strip()
    factor = float(factor) if factor else 1.1222
    device["attributes"] = device.get("attributes", {})
    device["attributes"]["odometerAnchorReal"] = real_km * 1000
    device["attributes"]["odometerAnchorDistance"] = 0
    device["attributes"]["odometerFactor"] = factor
    data = json.dumps(device).encode()
    req = urllib.request.Request(f"{BASE}/api/devices/{device['id']}", data=data, method="PUT",
                                  headers={"Content-Type": "application/json"})
    admin_opener.open(req).read()
    print(f"Set odometerAnchorReal={real_km * 1000}, odometerAnchorDistance=0, odometerFactor={factor}")

    print("\nDone. Don't forget: if ota@ultd.sk's password here differs from what's already in "
          "/opt/freematics-ota/traccar_credentials.json on the server, update that file to match.")


if __name__ == "__main__":
    main()
