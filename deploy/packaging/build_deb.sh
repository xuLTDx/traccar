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
chmod 755 "$PKGROOT"   # mktemp -d is 700; the package root maps to /

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
Depends: postgresql, python3 (>= 3.7), libreoffice-calc, curl, openssl
Recommends: iptables-persistent
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
 .
 libreoffice-calc: report export to ODS/PDF (DocumentConverter). The
 travel order PDF needs no system package (openhtmltopdf + Noto Sans are
 in Traccar's own lib/ and jar), only outbound HTTPS to static.slov-lex.sk
 (allowance rates, weekly) and api.statistics.sk (company lookup).
 .
 Firewall is NOT changed by this package (a wrong rule can lock the server
 out): open UDP 6000 (freematics) and TCP 6003 (freematicshttp) yourself,
 postinst prints the commands.
EOF

# --- maintainer scripts ---------------------------------------------------
cat > "$PKGROOT/DEBIAN/postinst" <<'EOF'
#!/bin/sh
set -e

echo "=== traccar-server-setup: PostgreSQL + traccar.xml setup ==="
echo "One password prompt below - used for both the Postgres role and"
echo "traccar.xml, so they can never end up out of sync. Safe to re-run on"
echo "a re-install: an existing role/database is detected and left as-is"
echo "(role password is updated to what you enter, database data is not"
echo "touched)."
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
echo ""
echo "Firewall (not changed by this package) - device ports to open:"
echo "  iptables -A INPUT -p udp --dport 6000 -j ACCEPT   # freematics"
echo "  iptables -A INPUT -p tcp --dport 6003 -j ACCEPT   # freematicshttp (OVMS)"
echo "  netfilter-persistent save"
exit 0
EOF
chmod 755 "$PKGROOT/DEBIAN/postinst"

OUT="traccar-server-setup_${VERSION}_all.deb"
dpkg-deb --build --root-owner-group "$PKGROOT" "$OUT"
echo "Built $(pwd)/$OUT"
