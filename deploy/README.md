# Deploy / bootstrap scripts

Written 2026-09-21 after a from-scratch PostgreSQL migration, so the exact
steps aren't lost in chat history. Run in order on a fresh server (or after
wiping the Traccar database and starting over).

No secrets are hardcoded in these scripts - each prompts for whatever
password it needs at runtime (never echoed, never written to disk by the
script itself). Safe to commit to a public-ish repo.

**Only run `02_configure_traccar.sh` directly - it asks for the Postgres
password ONCE and calls `01_bootstrap_postgres.sh` itself with that same
value.** Don't run `01` standalone and then `02` separately - that asks for
the password twice across two separate script runs with no shared state,
and if the role already existed (so `01`'s own password entry silently had
no effect), `02` would still go on to write whatever you typed the SECOND
time into `traccar.xml` and restart Traccar - a real near-miss caught live
on 2026-09-21 that could have broken the production DB connection. Both
scripts are now idempotent (safe to re-run against an existing
role/database - the role's password gets updated to match, the database
itself is left untouched) specifically so this can't happen again.

## `01_bootstrap_postgres.sh` + `02_configure_traccar.sh`

Installs PostgreSQL (if not already installed), creates/updates the
`traccar` database + role, and writes `/opt/traccar/conf/traccar.xml` with
that same password - preserving the rest of this deployment's config
(geocoder, motion threshold, port scheme - see the comments in the script
if any of that needs to change later). Restarts Traccar and confirms it
connects and creates its schema. Run as a user with sudo:

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

## `traccar.service`

Fixed copy of the systemd unit, adding `After=postgresql.service` /
`Wants=postgresql.service` - the live unit on the server was found
2026-09-21 to only have `After=network.target`, meaning on a reboot
Traccar could race PostgreSQL and start before it's ready (it would likely
recover via its own `Restart=on-failure`/`RestartSec=10`, but the ordering
should just be correct). Deploy with:

```
sudo cp deploy/traccar.service /etc/systemd/system/traccar.service
sudo systemctl daemon-reload
```

## Packaging (`packaging/build_deb.sh`)

Wraps steps 1-2 above (NOT step 3 - see why in the package description) into
a `traccar-server-setup` `.deb`, `Depends: postgresql, python3` so `apt
install` pulls PostgreSQL in automatically. Must be built ON the target
Debian server (needs `dpkg-deb`) - this repo is private, so `scp` the source
tree over rather than `git clone`-ing it on the server, same pattern as the
`freematics-ota` project. See that project's own `packaging/build_deb.sh`
for the established convention this follows.

```
scp -r deploy ultd@<server>:/tmp/traccar-deploy
ssh ultd@<server>
cd /tmp/traccar-deploy/packaging
bash build_deb.sh 1.0.0
sudo dpkg -i traccar-server-setup_1.0.0_all.deb
```

**As of 2026-09-21 this package definition has NOT been built or installed
on the real server - it's structure/logic-verified only (no `dpkg-deb`
access from the environment that wrote it).** Build and test-install it for
real before trusting it blindly on a future fresh server.

## Why this exists

Written the same day PostgreSQL replaced the embedded H2 database (which
required stopping the whole Traccar service for any direct SQL fix - real
friction hit repeatedly during 2026-09-20/21 troubleshooting). Postgres
removes that friction, but a fresh Postgres-backed Traccar starts with zero
data - none of the device/user/business-address/odometer config carries over
automatically. This directory exists so that setup is a repeatable, ~5-minute
script run, not something someone has to reconstruct from memory or chat
history years later.
