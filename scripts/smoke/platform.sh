#!/usr/bin/env bash
# Smoke test for the platform domain. Platform: web app, live push, outbox relay, login and roles.
# Sourced by scripts/smoke.sh (run: scripts/smoke.sh platform).
smoke_platform() {
fx_priya
step "Web app: served by nginx, talking to the BFF"
curl -sf "$WEB/" | grep -q "Sutherland Banking Platform" && pass "Web app is up at $WEB" || fail "Web app not served at $WEB"
me=$(curl -sf "$WEB/v1/me" -H "$PRIYA_H")
echo "$me" | grep -q '"accountId":"ACC-5501"' && pass "Home screen data through the web app: $(echo "$me" | head -c 160)…" \
  || fail "Unexpected /v1/me through the web app: $me"
step "Live push: the app's event stream receives Priya's incoming payment as it happens"
EVENTS=$(mktemp)
curl -sN --max-time 20 "$WEB/v1/events?customerId=$CUSTOMER${PRIYA_TOKEN:+&access_token=$PRIYA_TOKEN}" > "$EVENTS" &
CURL_PID=$!
sleep 3
curl -sf -X POST "$SIM/essence/v1/sim/inbound" -H 'Content-Type: application/json' \
  -d '{"creditAccountId":"ACC-5501","amount":"1.00","debtorName":"Live Push Test","reference":"SSE"}' >/dev/null
for _ in $(seq 1 15); do grep -q "payment.received" "$EVENTS" && break; sleep 1; done
kill $CURL_PID 2>/dev/null || true
wait $CURL_PID 2>/dev/null || true
grep -q "payment.received" "$EVENTS" && pass "Pushed over Server-Sent Events: $(grep -o '"type":"payment.received"[^}]*' "$EVENTS" | head -1 | head -c 120)…" \
  || fail "No payment.received event on the stream: $(head -c 400 "$EVENTS")"
step "Outbox relay: every event above travelled through service outbox tables and Debezium"
st=$(curl -sf "$CONNECT/connectors/pbp-outbox/status" || true)
if [ -n "$st" ]; then
  echo "$st" | grep -q '"state":"RUNNING"' && ! echo "$st" | grep -q '"state":"FAILED"' \
    && pass "Debezium outbox connector RUNNING (bff, orchestration, mapping, ehi outboxes)" || fail "Connector not healthy: $st"
else
  echo "  SKIP Kafka Connect not reachable at $CONNECT (services are in direct mode)"
fi
if [ "$AUTH" = 1 ]; then fx_asha; fi
if [ "$AUTH" = 1 ]; then
  step "Login: no token is refused; a view-only staff login cannot decide reviews"
  code=$(curl -s -o /dev/null -w '%{http_code}' "$BFF/v1/me")
  [ "$code" = "401" ] && pass "No token → 401" || fail "Expected 401 without a token, got $code"
  code=$(curl -s -o /dev/null -w '%{http_code}' "$BFF/v1/staff/queue" -H "Authorization: Bearer $PRIYA_TOKEN")
  [ "$code" = "403" ] && pass "Customer token on the staff API → 403" || fail "Expected 403 for a customer on /v1/staff, got $code"
  code=$(curl -s -o /dev/null -w '%{http_code}' "$BFF/v1/staff/queue" -H "Authorization: Bearer $OPS_TOKEN")
  [ "$code" = "200" ] && pass "Staff role can view the Control Room" || fail "Expected 200 for ops on the queue, got $code"
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BFF/v1/staff/recon/runs" -H "Authorization: Bearer $OPS_TOKEN")
  [ "$code" = "403" ] && pass "Staff without compliance cannot run reconciliation → 403" || fail "Expected 403 for ops, got $code"
  me=$(curl -sf "$BFF/v1/me" -H "Authorization: Bearer $ASHA_TOKEN")
  echo "$me" | grep -q "\"customerId\":\"$ASHA\"" && pass "Login 'asha' was linked to her customer when sign-up completed" \
    || fail "Login asha not linked to $ASHA: $me"
fi

step "Modules: the BFF and the orchestrator run exactly the modules in this build"
ORCH=${ORCH:-http://localhost:8081}
want=${PBP_MODULES:-onboarding,payments,compliance,cards,creditcards,recon}
bm=$(curl -sf "$BFF/v1/modules" -H "$PRIYA_H"); om=$(curl -sf "$ORCH/v1/modules")
for m in onboarding payments compliance cards creditcards recon; do
  on=0; case ",$want," in *",$m,"*) on=1 ;; esac
  got=0; echo "$bm" | grep -q "\"$m\"" && got=1
  [ "$got" = "$on" ] || fail "BFF module $m should be $([ "$on" = 1 ] && echo on || echo off) (PBP_MODULES=$want); /v1/modules says $bm"
  if [ "$m" != recon ]; then  # reconciliation runs in recon-service, not in the orchestrator
    got=0; echo "$om" | grep -q "\"$m\"" && got=1
    [ "$got" = "$on" ] || fail "Orchestrator module $m should be $([ "$on" = 1 ] && echo on || echo off); /v1/modules says $om"
  fi
done
pass "BFF $bm, orchestrator $om"
# A module that is off has no routes at all: each answers 404.
off_route() {  # module, method, path, header, label
  case ",$want," in *",$1,"*) return 0 ;; esac
  code=$(curl -s -o /dev/null -w '%{http_code}' -X "$2" "$BFF$3" -H "$4" -H 'Content-Type: application/json' -d '{}')
  [ "$code" = 404 ] && pass "$5 switched off: no route for $2 $3 (404)" || fail "$5 is off but $2 $3 answered $code"
}
off_route creditcards GET /v1/credit-cards "$PRIYA_H" "Credit cards"
off_route cards PUT /v1/cards/controls "$PRIYA_H" "Debit card controls"
off_route recon GET /v1/staff/recon/runs "$STAFF_H" "Reconciliation"
}
