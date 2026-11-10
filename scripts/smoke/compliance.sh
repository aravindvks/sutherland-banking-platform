#!/usr/bin/env bash
# Smoke test for the compliance domain. Compliance: a sanctions hit freezes the customer, inbound money is held, a staff review releases it.
# Sourced by scripts/smoke.sh (run: scripts/smoke.sh compliance).
smoke_compliance() {
fx_asha_funded
step "Sanctions hit: Atfinity rescreening freezes Asha$(module_on cards && echo " and her next tap is declined")"
CASE=$(curl -sf "$MAPPING/v1/customers/$ASHA/graph" | grep -o '"entityType":"KYC_CASE","externalId":"[^"]*"' | sed 's/.*"externalId":"\([^"]*\)"/\1/')
curl -sf -X POST "$SIM/atfinity/v1/sim/cases/$CASE/rescreen-hit" >/dev/null
if module_on cards; then
  for _ in $(seq 1 15); do
    tap=$(curl -sf -X POST "$SIM/thredd/v1/sim/card-tap" -H 'Content-Type: application/json' \
      -d "{\"token\":\"$ASHA_CARD\",\"amount\":\"1.00\",\"merchant\":\"Corner Shop\"}")
    echo "$tap" | grep -q '"reason":"CUSTOMER_FROZEN"' && break
    sleep 2
  done
  echo "$tap" | grep -q '"reason":"CUSTOMER_FROZEN"' && pass "Declined: CUSTOMER_FROZEN" || fail "Expected CUSTOMER_FROZEN, got $tap"
fi
for _ in $(seq 1 15); do
  status=$(curl -sf "$MAPPING/v1/customers/$ASHA" | field status)
  [ "$status" = "FROZEN" ] && break
  sleep 1
done
[ "$status" = "FROZEN" ] && pass "Asha's customer status is FROZEN" || fail "Expected FROZEN, got $status"
step "Inbound payments are recorded: COMPLETED for an active customer, FLAGGED once frozen"
list=$(curl -sf "$BFF/v1/payments" -H "$ASHA_H")
echo "$list" | grep -q '"paymentType":"INBOUND","status":"COMPLETED"' && pass "Asha's £100 welcome payment recorded as INBOUND COMPLETED" \
  || fail "No completed inbound payment for Asha: $list"
curl -sf -X POST "$SIM/essence/v1/sim/inbound" -H 'Content-Type: application/json' \
  -d "{\"creditAccountId\":\"$ASHA_ACC\",\"amount\":\"5.00\",\"debtorName\":\"Arjun Mehta\",\"reference\":\"Coffee\"}" >/dev/null
for _ in $(seq 1 15); do
  list=$(curl -sf "$BFF/v1/payments" -H "$ASHA_H")
  echo "$list" | grep -q '"status":"FLAGGED"' && break
  sleep 1
done
echo "$list" | grep -q '"status":"FLAGGED"' && pass "£5 to frozen Asha recorded as FLAGGED for review" || fail "Expected a FLAGGED inbound payment: $list"
step "Staff review: clearing Asha's case unfreezes her, unblocks her card and releases the held £5"
q=$(curl -sf "$BFF/v1/staff/queue" -H "$STAFF_H")
SAGA=$(echo "$q" | grep -o "\"sagaId\":\"[^\"]*\",\"customerId\":\"$ASHA\"" | head -1 | sed 's/"sagaId":"\([^"]*\)".*/\1/')
[ -n "$SAGA" ] && pass "Asha's case is in the review queue (saga $SAGA)" || fail "No open review for Asha: $q"
curl -sf -X POST "$BFF/v1/staff/reviews/$SAGA" -H 'Content-Type: application/json' -H "$STAFF_H" \
  -d '{"decision":"CLEAR","note":"False positive (smoke test)"}' >/dev/null
for _ in $(seq 1 15); do
  status=$(curl -sf "$MAPPING/v1/customers/$ASHA" | field status)
  [ "$status" = "ACTIVE" ] && break
  sleep 1
done
[ "$status" = "ACTIVE" ] && pass "Asha is ACTIVE again" || fail "Expected ACTIVE after CLEAR, got $status"
for _ in $(seq 1 15); do
  list=$(curl -sf "$BFF/v1/payments" -H "$ASHA_H")
  echo "$list" | grep -q '"status":"FLAGGED"' || break
  sleep 1
done
echo "$list" | grep -q '"status":"FLAGGED"' && fail "Held payment was not released: $list" || pass "Held £5 released (no FLAGGED payments left)"
if module_on cards; then
  for _ in $(seq 1 10); do
    tap=$(curl -sf -X POST "$SIM/thredd/v1/sim/card-tap" -H 'Content-Type: application/json' \
      -d "{\"token\":\"$ASHA_CARD\",\"amount\":\"1.00\",\"merchant\":\"Corner Shop\"}")
    echo "$tap" | grep -q '"decision":"APPROVE"' && break
    sleep 2
  done
  echo "$tap" | grep -q '"decision":"APPROVE"' && pass "Asha's card works again" || fail "Expected APPROVE after CLEAR, got $tap"
fi
}
