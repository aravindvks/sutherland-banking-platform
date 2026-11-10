#!/usr/bin/env bash
# Smoke test for the payments domain. Accounts and payments: same-bank and Faster Payments, scheme rejection, Confirmation of Payee, standing orders, Bacs, Direct Debits, CHAPS.
# Sourced by scripts/smoke.sh (run: scripts/smoke.sh payments).
smoke_payments() {
fx_payment
step "Other-bank payment: Priya pays £15 to Barclays (Finastra routes it and reports scheme status)"
resp=$(curl -sf -X POST "$BFF/v1/payments" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuid)" \
  -H "$PRIYA_H" \
  -d '{"debtorAccount":"ACC-5501","creditor":{"name":"Arjun Mehta","sortCode":"040004","accountNumber":"12345678"},"amount":{"value":"15.00","currency":"GBP"},"remittanceInfo":"Dinner","copResult":"MATCH"}')
OUT=$(echo "$resp" | field paymentId)
for _ in $(seq 1 20); do
  p=$(curl -s "$BFF/v1/payments/$OUT" -H "$PRIYA_H"); state=$(pstatus "$OUT")
  [ "$state" = "COMPLETED" ] || [ "$state" = "REJECTED" ] || [ "$state" = "FAILED" ] && break
  sleep 1
done
[ "$state" = "COMPLETED" ] && echo "$p" | grep -q '"to":"ACCEPTED"' && pass "FPS payment COMPLETED via SUBMITTED → ACCEPTED (Finastra $(echo "$p" | field finastraRef))" \
  || fail "Expected COMPLETED with an ACCEPTED step, got: $p"

step "Scheme rejection: a payment to sort code 99-00-00 is rejected and the money returned"
resp=$(curl -sf -X POST "$BFF/v1/payments" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuid)" \
  -H "$PRIYA_H" \
  -d '{"debtorAccount":"ACC-5501","creditor":{"name":"Nobody","sortCode":"990000","accountNumber":"31926819"},"amount":{"value":"10.00","currency":"GBP"},"remittanceInfo":"Test"}')
REJ=$(echo "$resp" | field paymentId)
for _ in $(seq 1 20); do
  p=$(curl -s "$BFF/v1/payments/$REJ" -H "$PRIYA_H"); state=$(pstatus "$REJ")
  [ "$state" = "REJECTED" ] || [ "$state" = "COMPLETED" ] || [ "$state" = "FAILED" ] && break
  sleep 1
done
[ "$state" = "REJECTED" ] && echo "$p" | grep -q 'PAY-SCHEME-001' && pass "REJECTED with PAY-SCHEME-001" || fail "Expected scheme rejection, got: $p"
step "Confirmation of Payee: the name is checked with the payee's bank before money moves"
cop() { curl -sf -X POST "$BFF/v1/payees/confirm" -H 'Content-Type: application/json' -H "$PRIYA_H" \
  -d "{\"name\":\"$1\",\"sortCode\":\"$2\",\"accountNumber\":\"$3\"}"; }
r=$(cop "Ravi Kumar" 200001 41550198); echo "$r" | grep -q '"result":"MATCH"' && pass "Ravi Kumar → MATCH" || fail "Expected MATCH, got $r"
r=$(cop "R Kumar" 200001 41550198)
echo "$r" | grep -q '"result":"CLOSE_MATCH"' && echo "$r" | grep -q '"matchedName":"Ravi Kumar"' \
  && pass "R Kumar → CLOSE_MATCH, account held by Ravi Kumar" || fail "Expected CLOSE_MATCH, got $r"
r=$(cop "John Smith" 200001 41550198); echo "$r" | grep -q '"result":"NO_MATCH"' && pass "John Smith → NO_MATCH" || fail "Expected NO_MATCH, got $r"
r=$(cop "Anyone" 980000 12345678); echo "$r" | grep -q '"result":"UNAVAILABLE"' && pass "Bank outside CoP → UNAVAILABLE" || fail "Expected UNAVAILABLE, got $r"
NM=$(pay '{"debtorAccount":"ACC-5501","creditor":{"name":"John Smith","sortCode":"200001","accountNumber":"41550198"},"amount":{"value":"5.00","currency":"GBP"},"remittanceInfo":"CoP test"}')
p=$(wait_payment "$NM" "REJECTED|COMPLETED|FAILED")
echo "$p" | grep -q '"status":"REJECTED"' && echo "$p" | grep -q 'PAY-COP-001' && pass "Payment to a name that does not match: REJECTED with PAY-COP-001" \
  || fail "Expected a PAY-COP-001 rejection, got $p"
CM=$(pay '{"debtorAccount":"ACC-5501","creditor":{"name":"R Kumar","sortCode":"200001","accountNumber":"41550198"},"amount":{"value":"5.00","currency":"GBP"},"remittanceInfo":"CoP test","copResult":"CLOSE_MATCH"}')
p=$(wait_payment "$CM" "REJECTED|COMPLETED|FAILED")
echo "$p" | grep -q '"status":"COMPLETED"' && echo "$p" | grep -q '"copResult":"CLOSE_MATCH"' \
  && pass "Close match the customer confirmed: COMPLETED, result kept on the payment" || fail "Expected COMPLETED after a confirmed close match, got $p"
step "Standing orders and scheduled payments (Temporal timers; each run is a normal payment saga)"
sched() { curl -sf -X POST "$BFF/v1/schedules" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuid)" -H "$PRIYA_H" -d "$1" | field scheduleId; }
SO=$(sched "{\"kind\":\"STANDING_ORDER\",\"frequency\":\"MONTHLY\",\"startDate\":\"$TODAY\",\"debtorAccount\":\"ACC-5501\",\"creditor\":{\"name\":\"Ravi Kumar\",\"sortCode\":\"200001\",\"accountNumber\":\"41550198\"},\"amount\":{\"value\":\"10.00\",\"currency\":\"GBP\"},\"reference\":\"Rent share\",\"copResult\":\"MATCH\"}")
[ -n "$SO" ] && pass "Monthly standing order $SO accepted, first payment today" || fail "No scheduleId for the standing order"
# Payments go out at 06:00 UK time. Before then today's payment is still waiting, so use the demo "pay now".
if [ "$(TZ=Europe/London date +%H)" -lt 6 ]; then
  for _ in $(seq 1 15); do curl -sf "$BFF/v1/schedules" -H "$PRIYA_H" | grep -q "\"scheduleId\":\"$SO\"" && break; sleep 1; done
  curl -sf -X POST "$BFF/v1/schedules/$SO/run-now" -H "$PRIYA_H" >/dev/null
  echo "  (before 06:00 UK: today's payment would wait until 06:00, so the demo 'pay now' sends it)"
fi
for _ in $(seq 1 25); do e=$(entry "$(curl -sf "$BFF/v1/schedules" -H "$PRIYA_H")" scheduleId "$SO" createdAt)
  echo "$e" | grep -q '"paymentsMade":1' && echo "$e" | grep -q '"lastStatus":"COMPLETED"' && break; sleep 1; done
echo "$e" | grep -q '"paymentsMade":1' && echo "$e" | grep -q '"lastStatus":"COMPLETED"' && ! echo "$e" | grep -q "\"nextDate\":\"$TODAY\"" \
  && pass "First payment made; next due $(echo "$e" | field nextDate)" || fail "Standing order did not pay today: $e"
curl -sf -X DELETE "$BFF/v1/schedules/$SO" -H "$PRIYA_H" >/dev/null
for _ in $(seq 1 15); do e=$(entry "$(curl -sf "$BFF/v1/schedules" -H "$PRIYA_H")" scheduleId "$SO" createdAt)
  echo "$e" | grep -q '"status":"CANCELLED"' && break; sleep 1; done
echo "$e" | grep -q '"status":"CANCELLED"' && pass "Standing order cancelled" || fail "Expected CANCELLED, got $e"
ONE=$(sched "{\"kind\":\"ONE_OFF\",\"startDate\":\"$TOMORROW\",\"debtorAccount\":\"ACC-5501\",\"creditor\":{\"name\":\"Ravi Kumar\",\"sortCode\":\"200001\",\"accountNumber\":\"41550198\"},\"amount\":{\"value\":\"7.00\",\"currency\":\"GBP\"},\"reference\":\"Tickets\",\"copResult\":\"MATCH\"}")
for _ in $(seq 1 15); do e=$(entry "$(curl -sf "$BFF/v1/schedules" -H "$PRIYA_H")" scheduleId "$ONE" createdAt)
  echo "$e" | grep -q "\"nextDate\":\"$TOMORROW\"" && break; sleep 1; done
echo "$e" | grep -q "\"nextDate\":\"$TOMORROW\"" && echo "$e" | grep -q '"paymentsMade":0' && pass "Payment scheduled for $TOMORROW, nothing paid yet" \
  || fail "Expected a payment due $TOMORROW, got $e"
curl -sf -X POST "$BFF/v1/schedules/$ONE/run-now" -H "$PRIYA_H" >/dev/null
for _ in $(seq 1 25); do e=$(entry "$(curl -sf "$BFF/v1/schedules" -H "$PRIYA_H")" scheduleId "$ONE" createdAt)
  echo "$e" | grep -q '"status":"COMPLETED"' && break; sleep 1; done
echo "$e" | grep -q '"status":"COMPLETED"' && echo "$e" | grep -q '"lastStatus":"COMPLETED"' && pass "Demo 'pay now': the scheduled payment was made and the schedule ended" \
  || fail "Expected the scheduled payment to complete, got $e"

step "Bacs Direct Credit: Priya's salary arrives in a Bacs file from her employer"
b=$(curl -sf -X POST "$SIM/essence/v1/sim/bacs/credits" -H 'Content-Type: application/json' \
  -d '{"originatorName":"ACME LTD","credits":[{"creditAccountId":"ACC-5501","amount":"2500.00","reference":"SALARY NOV"}]}')
BREF=$(echo "$b" | field finastraRef)
for _ in $(seq 1 20); do list=$(curl -sf "$BFF/v1/payments" -H "$PRIYA_H")
  echo "$list" | grep -q "\"status\":\"COMPLETED\",\"finastraRef\":\"$BREF\"" && break; sleep 1; done
echo "$list" | grep -q "\"paymentType\":\"INBOUND\",\"status\":\"COMPLETED\",\"finastraRef\":\"$BREF\"" \
  && pass "£2,500 from ACME LTD recorded as an INBOUND Bacs credit ($BREF)" || fail "Bacs credit $BREF not completed: $(echo "$list" | head -c 400)"

step "Direct Debits: a mandate, a collection paid, one returned unpaid, a Guarantee refund, then cancellation"
m=$(curl -sf -X POST "$SIM/essence/v1/sim/dd/mandates" -H 'Content-Type: application/json' \
  -d '{"accountId":"ACC-5501","originatorName":"Brightside Energy","serviceUserNumber":"275431","payerReference":"BE-PRIYA-01"}')
MREF=$(echo "$m" | field mandateRef)
for _ in $(seq 1 15); do dd=$(curl -sf "$BFF/v1/direct-debits" -H "$PRIYA_H"); e=$(entry "$dd" mandateRef "$MREF" createdAt)
  echo "$e" | grep -q '"status":"ACTIVE"' && break; sleep 1; done
MID=$(echo "$dd" | grep -o "\"mandateId\":\"[^\"]*\",\"mandateRef\":\"$MREF\"" | field mandateId)
[ -n "$MID" ] && pass "AUDDIS: Brightside Energy's mandate $MREF is ACTIVE on Priya's account" || fail "Mandate not set up: $dd"
collect() { curl -sf -X POST "$SIM/essence/v1/sim/dd/collections" -H 'Content-Type: application/json' \
  -d "{\"mandateRef\":\"$MREF\",\"amount\":\"$1\"}" | field collectionRef; }
dd_row() { for _ in $(seq 1 20); do list=$(curl -sf "$BFF/v1/payments" -H "$PRIYA_H")
  echo "$list" | grep -q "\"paymentType\":\"DIRECT_DEBIT\",\"status\":\"$2\",\"finastraRef\":\"$1\"" && { row_id "$list" "$1"; return; }
  sleep 1; done; }  # empty when the collection never reached that status
C1=$(collect 30.00); DD1=$(dd_row "$C1" COMPLETED)
[ -n "$DD1" ] && pass "£30 collection $C1 paid on its due date (payment $DD1)" || fail "Collection $C1 not paid: $(curl -sf "$BFF/v1/payments" -H "$PRIYA_H" | head -c 400)"
# More than Priya has (balances build up over runs, so it is worked out from her balance now).
BIG=$(awk "BEGIN { printf \"%.2f\", $(curl -sf "$SIM/essence/v1/accounts/ACC-5501/balance" | field available) + 1000 }")
C2=$(collect "$BIG"); DD2=$(dd_row "$C2" RETURNED)
p=$(curl -s "$BFF/v1/payments/$DD2" -H "$PRIYA_H")
echo "$p" | grep -q 'ARUDD 0' && pass "£$BIG collection (more than her balance) returned unpaid: ARUDD 0 (refer to payer)" || fail "Expected an ARUDD 0 return, got $p"
curl -sf -X POST "$BFF/v1/direct-debits/claims" -H 'Content-Type: application/json' -H "$PRIYA_H" -d "{\"paymentId\":\"$DD1\"}" >/dev/null
p=$(wait_payment "$DD1" "REFUNDED")
echo "$p" | grep -q '"status":"REFUNDED"' && pass "Direct Debit Guarantee: £30 refunded at once, indemnity claim raised on the originator" \
  || fail "Expected REFUNDED, got $p"
curl -sf -X DELETE "$BFF/v1/direct-debits/$MID" -H "$PRIYA_H" >/dev/null
for _ in $(seq 1 15); do e=$(entry "$(curl -sf "$BFF/v1/direct-debits" -H "$PRIYA_H")" mandateRef "$MREF" createdAt)
  echo "$e" | grep -q '"status":"CANCELLED"' && break; sleep 1; done
echo "$e" | grep -q '"status":"CANCELLED"' && pass "Mandate cancelled by Priya (ADDACS sent to the originator)" || fail "Expected CANCELLED, got $e"
C3=$(collect 30.00); DD3=$(dd_row "$C3" RETURNED)
p=$(curl -s "$BFF/v1/payments/$DD3" -H "$PRIYA_H")
echo "$p" | grep -q 'ARUDD 1' && pass "A later collection is returned: ARUDD 1 (instruction cancelled)" || fail "Expected an ARUDD 1 return, got $p"

step "CHAPS: a £12,000 house deposit is held for staff approval, then sent within CHAPS hours with a £20 fee"
curl -sf -X POST "$SIM/essence/v1/sim/inbound" -H 'Content-Type: application/json' \
  -d '{"creditAccountId":"ACC-5501","amount":"15000.00","debtorName":"Priya Shah","reference":"From savings"}' >/dev/null
sleep 2
CH=$(pay '{"debtorAccount":"ACC-5501","creditor":{"name":"Northwind Lettings Ltd","sortCode":"309634","accountNumber":"55667788"},"amount":{"value":"12000.00","currency":"GBP"},"remittanceInfo":"Deposit","copResult":"MATCH","scheme":"CHAPS"}')
p=$(wait_payment "$CH" "HELD|REJECTED|FAILED")
echo "$p" | grep -q '"status":"HELD"' && echo "$p" | grep -q '"paymentType":"CHAPS"' && pass "CHAPS payment $CH HELD for approval (£10,000 or more)" \
  || fail "Expected a HELD CHAPS payment, got $p"
q=$(curl -sf "$BFF/v1/staff/queue" -H "$STAFF_H")
echo "$q" | grep -q "\"paymentId\":\"$CH\"" && pass "It is in the staff Control Room queue" || fail "Held payment not in the staff queue: $q"
curl -sf -X POST "$BFF/v1/staff/payments/$CH/decision" -H 'Content-Type: application/json' -H "$STAFF_H" \
  -d '{"decision":"APPROVE","note":"Called the customer (smoke test)"}' >/dev/null
DOW=$(TZ=Europe/London date +%u); HM=$(TZ=Europe/London date +%H%M)
HOLIDAYS=" 2026-12-25 2026-12-28 2027-01-01 2027-03-26 2027-03-29 2027-05-03 2027-05-31 2027-08-30 2027-12-27 2027-12-28 "
if [ "$DOW" -le 5 ] && [ "$HM" -ge 0600 ] && [ "$HM" -lt 1730 ] && ! echo "$HOLIDAYS" | grep -q " $TODAY "; then
  p=$(wait_payment "$CH" "COMPLETED|REJECTED|FAILED|QUEUED")
  echo "$p" | grep -q '"status":"COMPLETED"' && echo "$p" | grep -q '"fee":"20.00"' && echo "$p" | grep -q 'Approved by' \
    && pass "Approved and settled by CHAPS; £20 fee charged" || fail "Expected COMPLETED with a £20 fee, got $p"
else
  p=$(wait_payment "$CH" "QUEUED|COMPLETED|REJECTED|FAILED")
  echo "$p" | grep -q '"status":"QUEUED"' && pass "Approved; CHAPS is closed now (UK $HM, day $DOW), so it waits for the next opening" \
    || fail "Expected QUEUED outside CHAPS hours, got $p"
fi
}
