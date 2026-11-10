#!/usr/bin/env bash
# Smoke test for the recon domain. Reconciliation: payments and card settlements match Essence; a rogue posting is caught.
# Sourced by scripts/smoke.sh (run: scripts/smoke.sh recon).
smoke_recon() {
fx_payment
fx_card_settled
step "Reconciliation: today's payments and card settlements all match Essence; a rogue posting is caught"
curl -sf -X POST "$SIM/essence/v1/sim/rogue-posting" -H 'Content-Type: application/json' -d '{"accountId":"ACC-5510","amount":"7.77"}' >/dev/null
rec=$(curl -s -w '\n%{http_code}' -X POST "$BFF/v1/staff/recon/runs" -H "$STAFF_H")
code=$(echo "$rec" | tail -1); rec=$(echo "$rec" | sed '$d')
[ "$code" = "200" ] || fail "Reconciliation run returned HTTP $code: $rec"
echo "$rec" | grep -q '"source":"PAYMENTS","matched":[1-9][0-9]*,"exceptions":0' && pass "PAYMENTS: all matched ($(echo "$rec" | grep -o '"source":"PAYMENTS"[^}]*'))" \
  || fail "Payments did not reconcile cleanly: $rec"
echo "$rec" | grep -q '"source":"CARDS","matched":[1-9][0-9]*,"exceptions":0' && pass "CARDS: all matched ($(echo "$rec" | grep -o '"source":"CARDS"[^}]*'))" \
  || fail "Card settlements did not reconcile cleanly: $rec"
exc=$(curl -sf "$BFF/v1/staff/recon/exceptions" -H "$STAFF_H")
echo "$exc" | grep -q '"type":"MISSING_IN_SOURCE","sourceRef":"ROGUE-' && pass "Rogue Essence posting reported as MISSING_IN_SOURCE" \
  || fail "Rogue posting not reported: $exc"
}
