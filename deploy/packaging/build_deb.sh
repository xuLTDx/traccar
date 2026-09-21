#!/bin/bash
# Builds traccar-server-setup_<version>_all.deb from the deploy/ scripts.
# Run this ON THE TARGET LINUX SERVER (needs dpkg-deb) - not on the Windows
# dev machine. This repo is private, so scp the source tree to the server
# first rather than git clone-ing it there.
#
# Usage: ./build_deb.sh [version]   (default version: 1.0.0)
set -euo pipefail
cd "$(dirname "$0")"
DEPLOY_DIR="$(dirname "$(pwd)")"   # .. from packaging/ is deploy/

VERSION="${1:-1.0.0}"
PKGROOT="$(mktemp -d)"
trap 'rm -rf "$PKGROOT"' EXIT

# --- filesystem layout -------------------------------------------------
install -d "$PKGROOT/DEBIAN"
install -d "$PKGROOT/opt/traccar-setup"

install -m 755 "$DEPLOY_DIR/01_bootstrap_postgres.sh"    "$PKGROOT/opt/traccar-setup/"
install -m 755 "$DEPLOY_DIR/02_configure_traccar.sh"     "$PKGROOT/opt/traccar-setup/"
install -m 755 "$DEPLOY_DIR/03_bootstrap_traccar_data.py" "$PKGROOT/opt/traccar-setup/"
install -m 644 "$DEPLOY_DIR/README.md"                    "$PKGROOT/opt/traccar-setup/"
if [ -f "$DEPLOY_DIR/traccar.service" ]; then
    install -d "$PKGROOT/lib/systemd/system"
    install -m 644 "$DEPLOY_DIR/traccar.service" "$PKGROOT/lib/systemd/system/"
fi

# --- control ------------------------------------------------------------
cat > "$PKGROOT/DEBIAN/control" <<EOF
Package: traccar-server-setup
Version: $VERSION
Section: net
Priority: optional
Architecture: all
Depends: postgresql, python3 (>= 3.7)
Maintainer: (self-hosted, no upstream)
Description: Bootstrap scripts for this deployment's Traccar + PostgreSQL setup
 Does NOT package Traccar itself (that's this fork's own Gradle build
 output, deployed to /opt/traccar separately) - this package only makes
 the PostgreSQL backend + Traccar config side of a fresh install
 reproducible, instead of reconstructing it from memory each time.
 .
 On install: creates the traccar Postgres role/database and points
 /opt/traccar/conf/traccar.xml at it (both interactive - you set the
 DB password at install time, nothing is auto-generated or hardcoded).
 .
 NOT run automatically (must be run manually once, after Traccar itself
 is confirmed up and responding): /opt/traccar-setup/03_bootstrap_traccar_data.py
 creates this deployment's actual user accounts, device, business
 addresses, and odometer calibration anchor - real per-deployment data
 that only makes sense entered by a human once, not on every install.
EOF

# --- maintainer scripts ---------------------------------------------------
cat > "$PKGROOT/DEBIAN/postinst" <<'EOF'
#!/bin/sh
set -e

echo "=== traccar-server-setup: PostgreSQL bootstrap ==="
echo "This will prompt for a new password for the 'traccar' Postgres role."
echo "If a 'traccar' database/role already exists, this step will fail on"
echo "the CREATE DATABASE/CREATE USER statements - that's expected on a"
echo "re-install, not a bug; skip ahead and just re-run"
echo "02_configure_traccar.sh by hand if you only need to update traccar.xml."
bash /opt/traccar-setup/01_bootstrap_postgres.sh

echo ""
echo "=== traccar-server-setup: configuring /opt/traccar/conf/traccar.xml ==="
echo "Enter the SAME password you just set above."
bash /opt/traccar-setup/02_configure_traccar.sh

if [ -f /lib/systemd/system/traccar.service ]; then
    systemctl daemon-reload || true
fi

echo ""
echo "=== Done. One manual step remains ==="
echo "Once Traccar is confirmed up (systemctl status traccar), run this ONCE"
echo "to create your users/device/business-addresses/odometer-anchor:"
echo "  python3 /opt/traccar-setup/03_bootstrap_traccar_data.py"
echo "See /opt/traccar-setup/README.md for what each account is for."
exit 0
EOF
chmod 755 "$PKGROOT/DEBIAN/postinst"

OUT="traccar-server-setup_${VERSION}_all.deb"
dpkg-deb --build --root-owner-group "$PKGROOT" "$OUT"
echo "Built $(pwd)/$OUT"
