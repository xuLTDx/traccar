# Deploy / bootstrap scripts

Written 2026-09-21 after a from-scratch PostgreSQL migration, so the exact
steps aren't lost in chat history. Run in order on a fresh server (or after
wiping the Traccar database and starting over).

No secrets are hardcoded in these scripts - each prompts for whatever
password it needs at runtime (never echoed, never written to disk by the
script itself). Safe to commit to a public-ish repo.

## 1. `01_bootstrap_postgres.sh`

Installs PostgreSQL (if not already installed) and creates the `traccar`
database + role. Run as a user with sudo. Prompts for the new `traccar`
Postgres role's password - remember it, you'll need it again in step 2.

```
sudo bash deploy/01_bootstrap_postgres.sh
```

## 2. `02_configure_traccar.sh`

Writes `/opt/traccar/conf/traccar.xml` with the PostgreSQL connection
(prompts for the same password from step 1), preserving the rest of this
deployment's config (geocoder, motion threshold, port scheme - see the
comments in the script if any of that needs to change later). Restarts
Traccar and confirms it connects and creates its schema.

```
sudo bash deploy/02_configure_traccar.sh
```

## 3. `03_bootstrap_traccar_data.py`

Pure Traccar REST API calls (Python stdlib only, no dependencies) - run
this AFTER Traccar is up and confirmed responding (step 2 did that). Creates,
in order:

1. The primary personal/admin account (becomes admin automatically - Traccar
   makes the first-ever registered user an admin). Prompts for email +
   password.
2. `ota@ultd.sk` - narrowly-scoped, non-admin, readonly account for
   `ota_push_watcher.py`. Prompts for password (must match whatever's in
   `/opt/freematics-ota/traccar_credentials.json` on the server, or update
   that file to match afterward).
3. `assist@ultd.sk` - admin-capable (not full system administrator, but
   `readonly=false`) account for ad-hoc maintenance/cleanup tasks (e.g. safe
   `DELETE /api/positions?deviceId=X` instead of raw SQL). Prompts for
   password.
4. The Freematics device (`ZKUCA42T`, "VW Passat RK859CL"), shared with all
   three accounts above.
5. The two known Business Addresses (home/company HQ on Karola Františka
   Palmu, and the Korytnická branch office) with their real coordinates.
6. The odometer calibration anchor device attributes
   (`odometerAnchorReal`/`odometerAnchorDistance`/`odometerFactor`) - prompts
   for the current real odometer reading in km, since that changes over time
   (don't blindly reuse an old value from a previous run of this script).

```
python3 deploy/03_bootstrap_traccar_data.py
```

## Why this exists

Written the same day PostgreSQL replaced the embedded H2 database (which
required stopping the whole Traccar service for any direct SQL fix - real
friction hit repeatedly during 2026-09-20/21 troubleshooting). Postgres
removes that friction, but a fresh Postgres-backed Traccar starts with zero
data - none of the device/user/business-address/odometer config carries over
automatically. This directory exists so that setup is a repeatable, ~5-minute
script run, not something someone has to reconstruct from memory or chat
history years later.
