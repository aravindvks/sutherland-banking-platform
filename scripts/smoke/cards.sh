#!/usr/bin/env bash
# Smoke test for the cards domain. Debit cards: authorisation, settlement, declines with reasons, card controls.
# Sourced by scripts/smoke.sh (run: scripts/smoke.sh cards).
smoke_cards() {
fx_tap
fx_asha_funded
step "Asha taps her new card (Thredd → EHI responder)"
tap=$(curl -sf -X POST "$SIM/thredd/v1/sim/card-tap" -H 'Content-Type: application/json' \
  -d "{\"token\":\"$ASHA_CARD\",\"amount\":\"3.50\",\"merchant\":\"Bean There Cafe\"}")
echo "$tap" | grep -q '"decision":"APPROVE"' && pass "Card tap approved: $tap" || fail "Expected APPROVE, got $tap"
step "Card settlement: the cafe settles the £3.50 tap (presentment captures the Essence hold)"
TAP_MSG=$(echo "$tap" | field messageId)
before=$(curl -sf "$SIM/essence/v1/accounts/$ASHA_ACC/balance")
echo "$before" | grep -q '"available":"96.50"' && echo "$before" | grep -q '"ledger":"100.00"' \
  && pass "Hold in place: available £96.50, ledger £100.00" || fail "Expected a £3.50 hold, got $before"
ack=$(curl -sf -X POST "$SIM/thredd/v1/sim/settle" -H 'Content-Type: application/json' \
  -d "{\"token\":\"$ASHA_CARD\",\"messageId\":\"$TAP_MSG\",\"amount\":\"3.50\"}")
after=$(curl -sf "$SIM/essence/v1/accounts/$ASHA_ACC/balance")
echo "$after" | grep -q '"ledger":"96.50"' && pass "Settled: $after" || fail "Expected ledger 96.50 after settlement, got $after ($ack)"
SETTLED=1
step "Declines with reasons: not enough money, and the daily card limit"
tap=$(curl -sf -X POST "$SIM/thredd/v1/sim/card-tap" -H 'Content-Type: application/json' \
  -d "{\"token\":\"$ASHA_CARD\",\"amount\":\"200.00\",\"merchant\":\"Volt Electronics\"}")
echo "$tap" | grep -q '"reason":"INSUFFICIENT_FUNDS"' && pass "£200 declined: INSUFFICIENT_FUNDS" || fail "Expected INSUFFICIENT_FUNDS, got $tap"
tap=$(curl -sf -X POST "$SIM/thredd/v1/sim/card-tap" -H 'Content-Type: application/json' \
  -d "{\"token\":\"$TOKEN\",\"amount\":\"350.00\",\"merchant\":\"Volt Electronics\"}")
echo "$tap" | grep -q '"reason":"LIMIT_EXCEEDED"' && pass "Priya's £350 declined: LIMIT_EXCEEDED (daily limit £300)" \
  || fail "Expected LIMIT_EXCEEDED, got $tap"
step "Card controls: Priya turns off online payments, raises her limit, then freezes her card"
ctl() { curl -sf -X PUT "$BFF/v1/cards/controls" -H 'Content-Type: application/json' -H "$PRIYA_H" -d "$1" >/dev/null; }
tapp() { curl -sf -X POST "$SIM/thredd/v1/sim/card-tap" -H 'Content-Type: application/json' \
  -d "{\"token\":\"$TOKEN\",\"amount\":\"$1\",\"merchant\":\"$2\",\"channel\":\"$3\"}"; }
ctl '{"frozen":false,"dailyLimit":"1000.00","online":false,"contactless":true,"atm":true}'
for _ in $(seq 1 15); do t=$(tapp 20.00 "Northwind Online" online); echo "$t" | grep -q CHANNEL_BLOCKED && break; sleep 1; done
echo "$t" | grep -q '"reason":"CHANNEL_BLOCKED"' && pass "Online purchase declined: CHANNEL_BLOCKED" || fail "Expected CHANNEL_BLOCKED, got $t"
t=$(tapp 350.00 "Volt Electronics" chip)
echo "$t" | grep -q '"decision":"APPROVE"' && pass "£350 approved under the new £1,000 limit" || fail "Expected APPROVE at £1,000 limit, got $t"
ctl '{"frozen":true,"dailyLimit":"1000.00","online":false,"contactless":true,"atm":true}'
for _ in $(seq 1 15); do t=$(tapp 2.00 "Bean There Cafe" contactless); echo "$t" | grep -q CARD_BLOCKED && break; sleep 1; done
echo "$t" | grep -q '"reason":"CARD_BLOCKED"' && pass "Frozen card declined: CARD_BLOCKED" || fail "Expected CARD_BLOCKED, got $t"
ctl '{"frozen":false,"dailyLimit":"300.00","online":true,"contactless":true,"atm":true}'
}
