#!/usr/bin/env bash
# End-to-end smoke test, using Priya, Ravi and Asha from the HLD flows. Needs: make up (all services running).
# Uses only curl, grep and sed.
#   scripts/smoke.sh                     every domain, in order
#   scripts/smoke.sh credit recon        only those domains (each sets up what it needs through fixtures)
# One file per domain in scripts/smoke/, shared helpers and fixtures in scripts/smoke/lib.sh.
set -Eeuo pipefail
# A bare `curl -sf` that gets HTTP 4xx/5xx exits 22 and set -e stops the script; say where, instead of a bare "Error 22".
trap 'rc=$?; printf "  \033[31mFAIL\033[0m line %s exited %s: %s\n" "$LINENO" "$rc" "$BASH_COMMAND" >&2' ERR

HERE=$(cd "$(dirname "$0")" && pwd)
ALL="onboarding payments cards compliance recon credit platform observability"
DOMAINS=${*:-$ALL}
for d in $DOMAINS; do [ -f "$HERE/smoke/$d.sh" ] || { echo "Unknown domain '$d'. Domains: $ALL"; exit 2; }; done

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
