#!/usr/bin/env bash
# End-to-end smoke test, using Priya, Ravi and Asha from the HLD flows. Needs: make up (all services running).
# Uses only curl, grep and sed.
#   scripts/smoke.sh                     every domain, in order
#   scripts/smoke.sh credit recon        only those domains (each sets up what it needs through fixtures)
#   PBP_MODULES=onboarding,payments,compliance scripts/smoke.sh   only the domains of the modules in that build
# One file per domain in scripts/smoke/, shared helpers and fixtures in scripts/smoke/lib.sh.
set -Eeuo pipefail
# A bare `curl -sf` that gets HTTP 4xx/5xx exits 22 and set -e stops the script; say where, instead of a bare "Error 22".
trap 'rc=$?; printf "  \033[31mFAIL\033[0m line %s exited %s: %s\n" "$LINENO" "$rc" "$BASH_COMMAND" >&2' ERR

HERE=$(cd "$(dirname "$0")" && pwd)
ALL="onboarding payments cards compliance recon credit platform observability"
# Module switches: with PBP_MODULES set (the modules this stack was built with), domains whose module is off are
# not tested. cards, credit and recon belong to switchable modules; the rest are always on.
MODULES=${PBP_MODULES:-onboarding,payments,compliance,cards,creditcards,recon}
module_of() { case "$1" in cards) echo cards ;; credit) echo creditcards ;; recon) echo recon ;; *) echo "" ;; esac; }
is_on() { local m; m=$(module_of "$1"); [ -z "$m" ] || case ",$MODULES," in *",$m,"*) return 0 ;; *) return 1 ;; esac; }
if [ $# -gt 0 ]; then DOMAINS="$*"; else DOMAINS=""; for d in $ALL; do is_on "$d" && DOMAINS="$DOMAINS $d"; done; DOMAINS=${DOMAINS# }; fi
for d in $DOMAINS; do
  [ -f "$HERE/smoke/$d.sh" ] || { echo "Unknown domain '$d'. Domains: $ALL"; exit 2; }
  is_on "$d" || { echo "Domain '$d' needs module '$(module_of "$d")', which is off in this build (PBP_MODULES=$MODULES)"; exit 2; }
done

DOMAIN=setup
. "$HERE/smoke/lib.sh"
for d in $ALL; do . "$HERE/smoke/$d.sh"; done
# Run in the canonical order whatever order they were named in.
for d in $ALL; do
  case " $DOMAINS " in *" $d "*) DOMAIN=$d; "smoke_$d" ;; esac
done

echo
echo "All checks passed ($DOMAINS)."
[ -n "${CORR:-}" ] && echo "Follow the payment in Grafana (http://localhost:3001): paste $CORR into the Overview dashboard." || true
