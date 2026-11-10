#!/usr/bin/env bash
# Shared by every domain's smoke test: service addresses, the wait for services, login, helpers and fixtures.
# Sourced by scripts/smoke.sh; not run on its own.
MAPPING=${MAPPING:-http://localhost:8082}
BFF=${BFF:-http://localhost:8080}
EHI=${EHI:-http://localhost:8083}
SIM=${SIM:-http://localhost:8090}
WEB=${WEB:-http://localhost:3000}
CONNECT=${CONNECT:-http://localhost:8088}
KC=${KC:-http://localhost:8180}
TEMPO=${TEMPO:-http://localhost:3200}
PROM=${PROM:-http://localhost:9090}
LOKI=${LOKI:-http://localhost:3100}

# Right after make up the Java services are still starting: wait until each answers its health check.
printf 'Waiting for services'
for port in 8090 8082 8084 8085 8086 8089 8083 8080 8081 8087; do
  for i in $(seq 1 120); do
    curl -sf "http://localhost:$port/actuator/health" >/dev/null 2>&1 && break
    [ "$i" = 120 ] && { echo; echo "Service on port $port did not become healthy within 4 minutes (docker compose ps)"; exit 1; }
    printf '.'; sleep 2
  done
done
# Keycloak (login) also starts slowly; wait for its realm if it is running at all.
if curl -s -o /dev/null "$KC/" 2>/dev/null || docker ps --format '{{.Names}}' 2>/dev/null | grep -q keycloak; then
  for i in $(seq 1 90); do curl -sf "$KC/realms/pbp" >/dev/null 2>&1 && break; printf '.'; sleep 2; done
fi
echo " ready"

# Login: with Keycloak up, every call carries a bearer token (as the web app does);
# otherwise the BFF runs with development headers.
token() { curl -sf -X POST "$KC/realms/pbp/protocol/openid-connect/token" -d grant_type=password \
  -d client_id=pbp-web -d "username=$1" -d "password=$2" | grep -o '"access_token":"[^"]*"' | sed 's/"access_token":"\(.*\)"/\1/'; }
if curl -sf "$KC/realms/pbp" >/dev/null 2>&1; then
  AUTH=1
  STAFF_TOKEN=$(token aravind staff123); PRIYA_TOKEN=$(token priya priya123); ASHA_TOKEN=$(token asha asha123)
  OPS_TOKEN=$(token ops ops123)
  STAFF_H="Authorization: Bearer $STAFF_TOKEN"; ASHA_H0="Authorization: Bearer $ASHA_TOKEN"
  [ -n "$STAFF_TOKEN" ] && [ -n "$PRIYA_TOKEN" ] && [ -n "$ASHA_TOKEN" ] || { echo "Could not sign in to Keycloak"; exit 1; }
else
  AUTH=0; STAFF_H="X-Staff-User: smoke-reviewer"; ASHA_H0="X-Smoke: 1"
fi


pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; exit 1; }
# Value of a JSON string field; empty (not a failure) when the field is not there yet, e.g. while polling.
field() { { grep -o "\"$1\":\"[^\"]*\"" || true; } | head -1 | sed "s/\"$1\":\"\(.*\)\"/\1/"; }
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen | tr 'A-Z' 'a-z'; }
# Top-level status of a payment (GET /v1/payments/{id}); history entries use from/to, not status.
pstatus() { curl -s "$BFF/v1/payments/$1" -H "$PRIYA_H" | { grep -o '"status":"[A-Z_]*"' || true; } | head -1 | sed 's/.*:"\(.*\)"/\1/'; }
# Waits until a payment reaches one of the statuses in $2 (a grep -E pattern); prints the payment.
wait_payment() { local p st; for _ in $(seq 1 "${3:-20}"); do st=$(pstatus "$1"); echo "$st" | grep -qE "^($2)$" && break; sleep 1; done
  curl -s "$BFF/v1/payments/$1" -H "$PRIYA_H"; }
# Payment ID of the row in a payments list with the given Finastra or Bacs reference.
row_id() { echo "$1" | { grep -oE "\"paymentId\":\"[^\"]*\",\"correlationId\":\"[^\"]*\",\"customerId\":\"[^\"]*\",\"debitAccount\":\"[^\"]*\",\"creditor\":\"(\\\\.|[^\"\\\\])*\",\"amount\":\"[^\"]*\",\"currency\":\"[^\"]*\",\"paymentType\":\"[^\"]*\",\"status\":\"[^\"]*\",\"finastraRef\":\"$2\"" || true; } | head -1 | field paymentId; }
# The fields of one schedule or mandate in a list, from its ID to the end of that entry.
entry() { echo "$1" | { grep -o "\"$2\":\"$3\".*" || true; } | sed "s/\"$4\".*//"; }
pay() { curl -sf -X POST "$BFF/v1/payments" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuid)" -H "$PRIYA_H" -d "$1" | field paymentId; }
# Numbered steps, labelled with the domain being tested ($DOMAIN is set by the runner).
N=0
step() { N=$((N+1)); echo "$N. [$DOMAIN] $1"; }
TODAY=$(TZ=Europe/London date +%F)
TOMORROW=$(TZ=Europe/London date -d '+1 day' +%F 2>/dev/null || TZ=Europe/London date -v+1d +%F)

# ---- Fixtures. Each sets up what a domain needs and runs once per smoke run, so a domain can be tested alone
# ---- or after the others. Their checks are real checks: a fixture that cannot set up its customer fails.

# Priya's customer record, vendor links, a fresh card token and £2,000 available. Sets CUSTOMER, TOKEN, PRIYA_H.
fx_priya() {
[ -n "${CUSTOMER:-}" ] && return 0
step "Priya's customer record and vendor links (mapping service)"
existing=$(curl -s "$MAPPING/v1/resolve?system=ESSENCE&type=ACCOUNT&id=ACC-5501" || true)
if echo "$existing" | grep -q '"customerId"'; then
  CUSTOMER=$(echo "$existing" | field customerId)
  ACCOUNT_REF=$(echo "$existing" | sed 's/.*"ref":{"refId":"\([^"]*\)".*/\1/')
  pass "Priya already linked: customer $CUSTOMER"
else
  CUSTOMER=$(curl -sf -X POST "$MAPPING/v1/customers" -H 'Content-Type: application/json' \
    -d '{"customerType":"INDIVIDUAL"}' | field customerId)
  PARTY_REF=$(curl -sf -X POST "$MAPPING/v1/customers/$CUSTOMER/refs" -H 'Content-Type: application/json' \
    -d '{"system":"ESSENCE","entityType":"PARTY","externalId":"ESS-PTY-88231"}' | field refId)
  ACCOUNT_REF=$(curl -sf -X POST "$MAPPING/v1/customers/$CUSTOMER/refs" -H 'Content-Type: application/json' \
    -d "{\"system\":\"ESSENCE\",\"entityType\":\"ACCOUNT\",\"externalId\":\"ACC-5501\",\"parentRefId\":\"$PARTY_REF\"}" | field refId)
  curl -sf -X PATCH "$MAPPING/v1/customers/$CUSTOMER/status" -H 'Content-Type: application/json' \
    -d '{"status":"ACTIVE","reason":"KYC approved (smoke test)"}' >/dev/null
  pass "Created customer $CUSTOMER with party and account links, status ACTIVE"
fi

TOKEN="thr_tok_smoke$(date +%s)"
curl -sf -X POST "$MAPPING/v1/customers/$CUSTOMER/refs" -H 'Content-Type: application/json' \
  -d "{\"system\":\"THREDD\",\"entityType\":\"CARD\",\"externalId\":\"$TOKEN\",\"parentRefId\":\"$ACCOUNT_REF\"}" >/dev/null
pass "Linked card token $TOKEN to account ACC-5501"
if [ "$AUTH" = 1 ]; then
  curl -sf -X POST "$BFF/v1/staff/logins" -H 'Content-Type: application/json' -H "$STAFF_H" \
    -d "{\"username\":\"priya\",\"customerId\":\"$CUSTOMER\"}" >/dev/null
  PRIYA_H="Authorization: Bearer $PRIYA_TOKEN"
  pass "Staff linked online banking login 'priya' to customer $CUSTOMER"
else
  PRIYA_H="X-Customer-Id: $CUSTOMER"
fi

# The simulator keeps balances between runs, and each run spends some of Priya's money: top her up to £2,000.
avail=$(curl -sf "$SIM/essence/v1/accounts/ACC-5501/balance" | field available)
topup=$(awk "BEGIN { t = 2000 - ${avail:-0}; if (t > 0) printf \"%.2f\", t }")
if [ -n "$topup" ]; then
  curl -sf -X POST "$SIM/essence/v1/sim/inbound" -H 'Content-Type: application/json' \
    -d "{\"creditAccountId\":\"ACC-5501\",\"amount\":\"$topup\",\"debtorName\":\"Priya Shah\",\"reference\":\"Test top-up\"}" >/dev/null
  pass "Topped up Priya's available balance from £$avail to £2,000.00 (balances persist between runs)"
fi
}

# A card tap on Priya's card at the EHI responder (also gives the observability checks a card trace).
fx_tap() {
[ -n "${TAPPED:-}" ] && return 0
fx_priya
step "Card tap: £3.50 at a cafe (EHI responder)"
auth=$(curl -sf -X POST "$EHI/ehi/v1/messages" -H 'Content-Type: application/json' \
  -d "{\"messageId\":\"EHI-$(uuid)\",\"messageType\":\"AUTHORISATION\",\"cardToken\":\"$TOKEN\",\"amount\":\"3.50\",\"currency\":\"GBP\",\"merchant\":{\"name\":\"Bean There Cafe\",\"mcc\":\"5814\",\"channel\":\"contactless\"}}")
echo "$auth" | grep -q '"decision":"APPROVE"' && pass "Approved: $auth" || fail "Expected APPROVE, got $auth"
TAPPED=1
}

# Priya pays Ravi £25 through the whole saga. Sets PAYMENT and CORR (used to find the payment's trace and logs).
fx_payment() {
[ -n "${CORR:-}" ] && return 0
fx_priya
step "Same-bank payment: Priya pays Ravi £25.00 (BFF → Kafka → Temporal → Essence adapter)"
before=$(curl -sf "$SIM/essence/v1/accounts/ACC-5510/balance" | field available)
KEY=$(uuid)
BODY='{"debtorAccount":"ACC-5501","creditor":{"name":"Ravi Kumar","sortCode":"200001","accountNumber":"41550198"},"amount":{"value":"25.00","currency":"GBP"},"purpose":"OTHER","remittanceInfo":"Smoke test","copResult":"MATCH"}'
resp=$(curl -sf -X POST "$BFF/v1/payments" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -H "$PRIYA_H" -d "$BODY")
PAYMENT=$(echo "$resp" | field paymentId)
CORR=$(echo "$resp" | field correlationId)
[ -n "$PAYMENT" ] && pass "202 Accepted: payment $PAYMENT, correlation $CORR" || fail "No paymentId in $resp"

replay=$(curl -sf -X POST "$BFF/v1/payments" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -H "$PRIYA_H" -d "$BODY")
[ "$(echo "$replay" | field paymentId)" = "$PAYMENT" ] && pass "Retry with the same Idempotency-Key returned the same payment" \
  || fail "Idempotency replay returned $replay"

expected=$(awk "BEGIN { printf \"%.2f\", $before + 25 }")
# 45 s: the first payment after a laptop wakes up can be slow (cold JVMs, Temporal and Kafka reconnecting).
for _ in $(seq 1 45); do
  after=$(curl -sf "$SIM/essence/v1/accounts/ACC-5510/balance" | field available)
  [ "$after" = "$expected" ] && break
  sleep 1
done
[ "$after" = "$expected" ] && pass "Ravi's Essence balance moved $before → $after" \
  || fail "Ravi's balance is $after, expected $expected. Payment status: $(pstatus "$PAYMENT" || echo unknown); outbox connector: $(curl -s "$CONNECT/connectors/pbp-outbox/status" | grep -o '"state":"[A-Z]*"' | tr '\n' ' ' || echo unreachable). INITIATED with a FAILED connector = events not flowing (restart it, see README); a later status = check the workflow in Temporal UI (http://localhost:8233)"
}

# Asha signs up through the onboarding saga. Sets ASHA, ASHA_H, ASHA_ACC, ASHA_CARD.
fx_asha() {
[ -n "${ASHA:-}" ] && return 0
step "Onboarding: Asha signs up (BFF → Temporal → mapping → Atfinity → Essence → Thredd)"
onb=$(curl -sf -X POST "$BFF/v1/onboarding" -H 'Content-Type: application/json' -H "$ASHA_H0" -H "Idempotency-Key: $(uuid)" \
  -d '{"customerType":"INDIVIDUAL","name":"Asha Patel","email":"asha@example.com"}')
ONB=$(echo "$onb" | field onboardingId)
[ -n "$ONB" ] && pass "202 Accepted: onboarding $ONB" || fail "No onboardingId in $onb"
for _ in $(seq 1 45); do
  st=$(curl -s "$BFF/v1/onboarding/$ONB" -H "$ASHA_H0")
  state=$(echo "$st" | field status)
  [ "$state" = "COMPLETED" ] || [ "$state" = "FAILED" ] || [ "$state" = "REJECTED" ] && break
  sleep 2
done
[ "$state" = "COMPLETED" ] && pass "Onboarding COMPLETED: $st" || fail "Onboarding ended as '$state': $st"
ASHA=$(echo "$st" | field customerId)
if [ "$AUTH" = 1 ]; then ASHA_H="Authorization: Bearer $ASHA_TOKEN"; else ASHA_H="X-Customer-Id: $ASHA"; fi
ASHA_ACC=$(echo "$st" | field accountId)
cust=$(curl -sf "$MAPPING/v1/customers/$ASHA")
echo "$cust" | grep -q '"status":"ACTIVE"' && echo "$cust" | grep -q '"kycStatus":"VALID"' \
  && pass "Customer ACTIVE with KYC VALID (orchestrator PATCH calls to the mapping service work)" \
  || fail "Expected ACTIVE and KYC VALID, got $cust"
ASHA_CARD=$(curl -sf "$MAPPING/v1/customers/$ASHA/graph" | { grep -o '"entityType":"CARD","externalId":"[^"]*"' || true; } | sed 's/.*"externalId":"\([^"]*\)"/\1/')
if module_on cards; then
  [ -n "$ASHA_CARD" ] && pass "Mapping holds Asha's KYC case, party, account $ASHA_ACC and card $ASHA_CARD" \
    || fail "No card linked for customer $ASHA"
else
  # A bank without the debit cards module (e.g. corporate banking) opens the account and issues no card.
  [ -z "$ASHA_CARD" ] && ! echo "$st" | grep -q '"cardLast4":"[0-9]' \
    && pass "Debit cards module off: account $ASHA_ACC opened and no card issued" \
    || fail "Debit cards module is off but onboarding issued card '${ASHA_CARD}': $st"
fi
}

# Asha has £100, recorded by the bank as a completed inbound payment.
fx_asha_funded() {
[ -n "${ASHA_FUNDED:-}" ] && return 0
fx_asha
step "Asha receives £100 by Faster Payment"
curl -sf -X POST "$SIM/essence/v1/sim/inbound" -H 'Content-Type: application/json' \
  -d "{\"creditAccountId\":\"$ASHA_ACC\",\"amount\":\"100.00\",\"debtorName\":\"Arjun Mehta\",\"reference\":\"Welcome\"}" >/dev/null
bal=$(curl -sf "$SIM/essence/v1/accounts/$ASHA_ACC/balance" | field available)
[ "$bal" = "100.00" ] && pass "Asha's balance is £$bal" || fail "Expected 100.00, got $bal"
# Let the bank record the £100 (inbound saga) before step 9 freezes Asha, or it is correctly FLAGGED instead.
for _ in $(seq 1 30); do curl -sf "$BFF/v1/payments" -H "$ASHA_H" | grep -q '"paymentType":"INBOUND","status":"COMPLETED"' && break; sleep 1; done
ASHA_FUNDED=1
}

# At least one settled card payment today, for reconciliation.
fx_card_settled() {
[ -n "${SETTLED:-}" ] && return 0
fx_priya
step "A £2.00 card payment on Priya's card, settled by the merchant"
t=$(curl -sf -X POST "$SIM/thredd/v1/sim/card-tap" -H 'Content-Type: application/json' \
  -d "{\"token\":\"$TOKEN\",\"amount\":\"2.00\",\"merchant\":\"Bean There Cafe\",\"channel\":\"contactless\"}")
echo "$t" | grep -q '"decision":"APPROVE"' || fail "Expected APPROVE, got $t"
curl -sf -X POST "$SIM/thredd/v1/sim/settle" -H 'Content-Type: application/json' \
  -d "{\"token\":\"$TOKEN\",\"messageId\":\"$(echo "$t" | field messageId)\",\"amount\":\"2.00\"}" >/dev/null
pass "Tapped and settled £2.00"
SETTLED=1
}
