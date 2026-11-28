#!/usr/bin/env bash
# End-to-end smoke test for a market other than the UK (docs/design/market-packs.md, Stage 2), on the simulators.
# Needs the stack built and started with the same market:
#   PBP_MARKET=us ./gradlew bootJar && PBP_MARKET=us PBP_BANK_CODE=026009593 docker compose ... up -d
#   PBP_MARKET=us scripts/smoke-market.sh
# Checks that the BFF describes the market, that the market's account check refuses a bad account, and that each
# of the market's payment schemes goes from the BFF through Kafka, Temporal, the Essence adapter and the simulator
# to a final status, with no payee name check. UK banks keep using scripts/smoke.sh.
set -Eeuo pipefail
trap 'rc=$?; printf "  \033[31mFAIL\033[0m line %s exited %s: %s\n" "$LINENO" "$rc" "$BASH_COMMAND" >&2' ERR

HERE=$(cd "$(dirname "$0")" && pwd)
MARKET=${PBP_MARKET:-}
[ -n "$MARKET" ] && [ "$MARKET" != uk ] || { echo "Set PBP_MARKET to a market other than uk (us, jm, ae, co)"; exit 2; }

DOMAIN=setup
. "$HERE/smoke/lib.sh"
DOMAIN=$MARKET

# Per market: currency, a valid payee account, an invalid one, and each scheme to try with what the customer picks
# (empty: the router's default) and the statuses that count as done. A scheme with opening hours may be QUEUED,
# depending on the time of the run; that is the queue working, not a failure.
case "$MARKET" in
  us)
    CUR=USD
    GOOD='{"scheme":"US_ABA","fields":{"routingNumber":"021000021","accountNumber":"000123456789","accountType":"CHECKING"}}'
    BAD='{"scheme":"US_ABA","fields":{"routingNumber":"021000022","accountNumber":"000123456789","accountType":"CHECKING"}}'
    TRIES="RTP:-:COMPLETED ACH:ACH:COMPLETED WIRE:WIRE:COMPLETED|QUEUED" ;;
  jm)
    CUR=JMD
    GOOD='{"scheme":"JM_ACCOUNT","fields":{"bankCode":"12","branchCode":"10101","accountNumber":"123456789"}}'
    BAD='{"scheme":"JM_ACCOUNT","fields":{"bankCode":"12","branchCode":"10101","accountNumber":"12"}}'
    TRIES="ACH:-:COMPLETED RTGS:RTGS:COMPLETED|QUEUED" ;;
  ae)
    CUR=AED
    GOOD='{"scheme":"IBAN","fields":{"iban":"AE420260001015333439101"}}'
    BAD='{"scheme":"IBAN","fields":{"iban":"AE430260001015333439101"}}'
    TRIES="AANI:-:COMPLETED UAEFTS:UAEFTS:COMPLETED|QUEUED" ;;
  co)
    CUR=COP
    GOOD='{"scheme":"CO_ACCOUNT","fields":{"bankCode":"1007","accountNumber":"123456789","accountType":"SAVINGS"}}'
    BAD='{"scheme":"CO_ACCOUNT","fields":{"bankCode":"1007","accountNumber":"123456789","accountType":"AHORROS"}}'
    TRIES="BREB:-:COMPLETED ACH:ACH:COMPLETED CUD:CUD:COMPLETED|QUEUED" ;;
  *) echo "No smoke test for market '$MARKET'"; exit 2 ;;
esac

# Priya's login and money first: every BFF call, /v1/market included, needs a signed-in customer.
fx_priya

step "The BFF describes the $MARKET market"
m=$(curl -sf "$BFF/v1/market" -H "$PRIYA_H")
[ "$(echo "$m" | field code)" = "$MARKET" ] && [ "$(echo "$m" | field currency)" = "$CUR" ] \
  && pass "GET /v1/market: $MARKET, $CUR" || fail "Expected $MARKET and $CUR, got $m"
echo "$m" | grep -q '"nameCheck"' && fail "The $MARKET pack has no name check, but /v1/market lists one" \
  || pass "No payee name check in this market"


step "The market's account check refuses a bad payee account"
code=$(curl -s -o /tmp/bad.json -w '%{http_code}' -X POST "$BFF/v1/payments" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuid)" -H "$PRIYA_H" \
  -d "{\"debtorAccount\":\"ACC-5501\",\"creditor\":{\"name\":\"Pat Lee\",\"account\":$BAD},\"amount\":{\"value\":\"5.00\",\"currency\":\"$CUR\"},\"remittanceInfo\":\"Bad account\"}")
[ "$code" = 400 ] && pass "Refused with 400: $(head -c 300 /tmp/bad.json)" || fail "Expected 400, got $code: $(cat /tmp/bad.json)"

for t in $TRIES; do
  want=${t%%:*}; rest=${t#*:}; chosen=${rest%%:*}; done_when=${rest#*:}
  [ "$chosen" = - ] && sch="" || sch=",\"scheme\":\"$chosen\""
  step "A ${want} payment from the BFF to a final status"
  id=$(pay "{\"debtorAccount\":\"ACC-5501\",\"creditor\":{\"name\":\"Pat Lee\",\"account\":$GOOD},\"amount\":{\"value\":\"10.00\",\"currency\":\"$CUR\"},\"remittanceInfo\":\"Smoke $want\"$sch}")
  [ -n "$id" ] || fail "The BFF did not accept the $want payment"
  p=$(wait_payment "$id" "$done_when|REJECTED|FAILED" 40)
  st=$(pstatus "$id")
  echo "$st" | grep -qE "^($done_when)$" || fail "$want payment $id ended $st: $(echo "$p" | head -c 900)"
  type=$(echo "$p" | field paymentType)
  [ "$type" = "$want" ] || fail "Expected scheme $want, the payment went by $type: $(echo "$p" | head -c 600)"
  echo "$p" | grep -q 'No payee name check in this market' || fail "The payment history does not show the name check skipped"
  pass "$want payment $id is $st (scheme $type, name check skipped)"
done

echo
echo "All checks passed ($MARKET market)."
