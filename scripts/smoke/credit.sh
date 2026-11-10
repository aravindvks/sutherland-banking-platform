#!/usr/bin/env bash
# Smoke test for the credit domain. Credit cards: application, open-to-buy, repayments, statements, arrears, underwriting, withdrawal.
# Sourced by scripts/smoke.sh (run: scripts/smoke.sh credit).
smoke_credit() {
fx_priya
fx_asha
# GET /v1/credit-cards for a customer header; part of it from a list key onwards (one account per customer here).
cc() { curl -sf "$BFF/v1/credit-cards" -H "$1"; }
from_key() { echo "$1" | sed -n "s/.*\"$2\":\[\(.*\)/\1/p"; }
cc_wait() { local j; for _ in $(seq 1 "${3:-30}"); do j=$(cc "$1"); echo "$j" | grep -qE "$2" && break; sleep 1; done; echo "$j"; }
cc_apply() { curl -sf -X POST "$BFF/v1/credit-cards/applications" -H 'Content-Type: application/json' -H "$1" -d "$2" | field applicationId; }
# 1 when a − b equals d to the penny.
diff_is() { awk "BEGIN { print (sprintf(\"%.2f\", $1 - $2) == sprintf(\"%.2f\", $3)) }"; }
cc_repay() { curl -sf -X POST "$BFF/v1/credit-cards/$2/repayments" -H 'Content-Type: application/json' -H "$1" \
  -d "{\"debitAccount\":\"$3\",\"amount\":\"$4\"}" | field repaymentId; }
step "Credit card: Priya applies (soft search), the decision engine approves £1,500, she accepts and her Thredd credit card opens"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BFF/v1/credit-cards/applications" -H 'Content-Type: application/json' -H "$PRIYA_H" \
  -d '{"name":"Priya Shah","annualIncome":"42000","employmentStatus":"EMPLOYED","requestedLimit":"1500","consentToSoftSearch":false}')
[ "$code" = 400 ] && pass "No consent to the soft search → 400" || fail "Expected 400 without consent, got $code"
j=$(cc "$PRIYA_H")
if from_key "$j" accounts | grep -qE '"status":"(ACTIVE|ARREARS)"'; then
  pass "Priya already has a credit card from an earlier run; reusing it"
else
  APP=$(cc_apply "$PRIYA_H" '{"name":"Priya Shah","annualIncome":"42000","employmentStatus":"EMPLOYED","monthlyHousing":"1100","monthlyCommitments":"150","requestedLimit":"1500","consentToSoftSearch":true}')
  [ -n "$APP" ] && pass "Application $APP accepted (202)" || fail "Credit card application was not accepted"
  j=$(cc_wait "$PRIYA_H" "\"applicationId\":\"$APP\",\"status\":\"(OFFERED|DECLINED|REFERRED)\"" 30)
  echo "$j" | grep -q "\"applicationId\":\"$APP\",\"status\":\"OFFERED\",\"requestedLimit\":\"1500.00\",\"offeredLimit\":\"1500.00\",\"purchaseApr\":\"24.90\"" \
    && pass "Decision engine approved: offer of £1,500 at 24.9% APR" || fail "Expected an OFFERED £1,500 at 24.9%: $(echo "$j" | head -c 400)"
  curl -sf -X POST "$BFF/v1/credit-cards/applications/$APP/accept" -H 'Content-Type: application/json' -H "$PRIYA_H" -d '{"agreementVersion":"v1"}' >/dev/null
  j=$(cc_wait "$PRIYA_H" '"accounts":\[\{"accountId":"thr_cca_' 30)
fi
CC=$(from_key "$j" accounts | field accountId); CC_TOKEN=$(from_key "$j" accounts | field cardToken)
[ -n "$CC" ] && [ -n "$CC_TOKEN" ] && pass "Thredd credit account $CC with credit card $CC_TOKEN, limit $(from_key "$j" accounts | field creditLimit)" \
  || fail "No credit card account for Priya: $(echo "$j" | head -c 400)"

step "Credit card taps use Thredd's open-to-buy: approved within the limit, declined over it, settled onto the card (not Essence)"
ess_before=$(curl -sf "$SIM/essence/v1/accounts/ACC-5501/balance" | field available)
tap=$(curl -sf -X POST "$SIM/thredd/v1/sim/card-tap" -H 'Content-Type: application/json' \
  -d "{\"token\":\"$CC_TOKEN\",\"amount\":\"40.00\",\"merchant\":\"Volt Electronics\",\"channel\":\"chip\"}")
echo "$tap" | grep -q '"decision":"APPROVE"' && pass "£40 approved against the credit limit: $tap" || fail "Expected APPROVE on the credit card, got $tap"
CC_MSG=$(echo "$tap" | field messageId)
avail=$(from_key "$(cc "$PRIYA_H")" accounts | field availableCredit)
over=$(awk "BEGIN { printf \"%.2f\", $avail + 10 }")
tap2=$(curl -sf -X POST "$SIM/thredd/v1/sim/card-tap" -H 'Content-Type: application/json' \
  -d "{\"token\":\"$CC_TOKEN\",\"amount\":\"$over\",\"merchant\":\"Volt Electronics\",\"channel\":\"chip\"}")
echo "$tap2" | grep -q '"reason":"CREDIT_LIMIT_EXCEEDED"' && pass "£$over (available £$avail) declined CREDIT_LIMIT_EXCEEDED" \
  || fail "Expected CREDIT_LIMIT_EXCEEDED for £$over, got $tap2"
bal_before=$(from_key "$(cc "$PRIYA_H")" accounts | field balance)
curl -sf -X POST "$SIM/thredd/v1/sim/settle" -H 'Content-Type: application/json' \
  -d "{\"token\":\"$CC_TOKEN\",\"messageId\":\"$CC_MSG\",\"amount\":\"40.00\"}" >/dev/null
bal_after=$(from_key "$(cc "$PRIYA_H")" accounts | field balance)
[ "$(diff_is "$bal_after" "$bal_before" 40)" = 1 ] && pass "Settlement posted to the Thredd balance: $bal_before → $bal_after" \
  || fail "Thredd balance $bal_before → $bal_after, expected +40"
ess_after=$(curl -sf "$SIM/essence/v1/accounts/ACC-5501/balance" | field available)
[ "$ess_before" = "$ess_after" ] && pass "Priya's Essence current account untouched by the credit card ($ess_after)" \
  || fail "Essence balance moved $ess_before → $ess_after on a credit card tap"

step "Repayment: £25 from Priya's current account to her credit card (Essence → bank's card account → Thredd)"
REP=$(cc_repay "$PRIYA_H" "$CC" ACC-5501 25.00)
j=$(cc_wait "$PRIYA_H" "\"repaymentId\":\"$REP\",\"amount\":\"25.00\",\"status\":\"(COMPLETED|FAILED|REFUNDED)\"" 30)
echo "$j" | grep -q "\"repaymentId\":\"$REP\",\"amount\":\"25.00\",\"status\":\"COMPLETED\"" && pass "Repayment $REP COMPLETED" \
  || fail "Repayment not completed: $(from_key "$j" repayments | head -c 300)"
bal=$(from_key "$j" accounts | field balance)
[ "$(diff_is "$bal_after" "$bal" 25)" = 1 ] && pass "Thredd balance $bal_after → $bal" || fail "Thredd balance $bal_after → $bal, expected −25"
ess=$(curl -sf "$SIM/essence/v1/accounts/ACC-5501/balance" | field available)
[ "$(diff_is "$ess_after" "$ess" 25)" = 1 ] && pass "Essence current account debited £25 ($ess_after → $ess)" \
  || fail "Essence balance $ess_after → $ess, expected −25"

step "Statements: Thredd's statement is paid at the minimum; the next is missed, so a £12 late fee is charged and the card goes into arrears"
# A reused card can be in credit after many local runs; a statement on it has a £0.00 minimum and nothing to test.
# Spend on the card until it owes £100, so the statement has a real minimum payment.
bal=$(from_key "$(cc "$PRIYA_H")" accounts | field balance)
if [ "$(awk "BEGIN { print ($bal < 50) }")" = 1 ]; then
  top=$(awk "BEGIN { printf \"%.2f\", 100 - $bal }")
  t=$(curl -sf -X POST "$SIM/thredd/v1/sim/card-tap" -H 'Content-Type: application/json' \
    -d "{\"token\":\"$CC_TOKEN\",\"amount\":\"$top\",\"merchant\":\"Volt Electronics\",\"channel\":\"chip\"}")
  echo "$t" | grep -q '"decision":"APPROVE"' || fail "Could not top up the card balance (£$top): $t"
  curl -sf -X POST "$SIM/thredd/v1/sim/settle" -H 'Content-Type: application/json' \
    -d "{\"token\":\"$CC_TOKEN\",\"messageId\":\"$(echo "$t" | field messageId)\",\"amount\":\"$top\"}" >/dev/null
  echo "  (card was £$bal; spent £$top so it owes £100 before the statement)"
fi
st=$(curl -sf -X POST "$SIM/thredd/v1/credit/sim/statement" -H 'Content-Type: application/json' -d "{\"accountId\":\"$CC\",\"dueInSeconds\":15}")
STMT=$(echo "$st" | field statementId); MIN=$(echo "$st" | field minimumPayment)
[ -n "$STMT" ] && pass "Statement $STMT: balance £$(echo "$st" | field balance), minimum £$MIN" || fail "No statement from Thredd: $st"
cc_wait "$PRIYA_H" "\"statementId\":\"$STMT\"" 20 >/dev/null
REP=$(cc_repay "$PRIYA_H" "$CC" ACC-5501 "$MIN")
j=$(cc_wait "$PRIYA_H" "\"statementId\":\"$STMT\",[^}]*\"status\":\"(MINIMUM_PAID|PAID_IN_FULL|MISSED)\"" 45)
echo "$j" | grep -qE "\"statementId\":\"$STMT\",[^}]*\"status\":\"(MINIMUM_PAID|PAID_IN_FULL)\"" && pass "Minimum £$MIN paid by the due time: statement settled" \
  || fail "Statement $STMT not settled as paid: $(from_key "$j" statements | head -c 300)"
st=$(curl -sf -X POST "$SIM/thredd/v1/credit/sim/statement" -H 'Content-Type: application/json' -d "{\"accountId\":\"$CC\",\"dueInSeconds\":8}")
STMT2=$(echo "$st" | field statementId)
j=$(cc_wait "$PRIYA_H" "\"statementId\":\"$STMT2\",[^}]*\"status\":\"(MISSED|MINIMUM_PAID|PAID_IN_FULL)\"" 45)
echo "$j" | grep -q "\"statementId\":\"$STMT2\",[^}]*\"status\":\"MISSED\",\"lateFee\":\"12.00\"" && pass "Statement $STMT2 MISSED: £12 late fee charged by Thredd" \
  || fail "Expected statement $STMT2 MISSED with a £12 fee: $(from_key "$j" statements | head -c 300)"
from_key "$j" accounts | grep -q '"status":"ARREARS"' && pass "Credit card account in ARREARS (shown in the Control Room)" \
  || fail "Account not in arrears: $(from_key "$j" accounts | head -c 200)"
curl -sf "$BFF/v1/staff/queue" -H "$STAFF_H" | grep -q "\"creditArrears\":\[{\"accountId\":\"$CC\"" && pass "Control Room lists the account under arrears" \
  || fail "Arrears not in the staff queue"
# Leave the card clear for the next run: repay what Thredd says is owed.
owed=$(from_key "$(cc "$PRIYA_H")" accounts | field balance)
[ "$(awk "BEGIN { print ($owed > 0) }")" = 1 ] && cc_repay "$PRIYA_H" "$CC" ACC-5501 "$owed" >/dev/null

step "Underwriting and withdrawal: Asha's £8,000 request is referred, approved by staff at £6,000, opened, then withdrawn within 14 days; a bureau decline"
APP=$(cc_apply "$ASHA_H" '{"name":"Asha Verma","annualIncome":"90000","employmentStatus":"EMPLOYED","monthlyHousing":"1200","monthlyCommitments":"0","requestedLimit":"8000","consentToSoftSearch":true}')
j=$(cc_wait "$ASHA_H" "\"applicationId\":\"$APP\",\"status\":\"(REFERRED|OFFERED|DECLINED)\"" 30)
echo "$j" | grep -q "\"applicationId\":\"$APP\",\"status\":\"REFERRED\"" && pass "Referred for manual underwriting (over £5,000)" \
  || fail "Expected REFERRED: $(echo "$j" | head -c 300)"
curl -sf "$BFF/v1/staff/credit-applications" -H "$STAFF_H" | grep -q "\"applicationId\":\"$APP\"" && pass "In the underwriting queue" || fail "Not in the underwriting queue"
curl -sf -X POST "$BFF/v1/staff/credit-applications/$APP/decision" -H 'Content-Type: application/json' -H "$STAFF_H" \
  -d '{"decision":"APPROVE","creditLimit":"6000.00","note":"Income verified (smoke test)"}' >/dev/null
j=$(cc_wait "$ASHA_H" "\"applicationId\":\"$APP\",\"status\":\"OFFERED\"" 20)
echo "$j" | grep -q "\"applicationId\":\"$APP\",\"status\":\"OFFERED\",\"requestedLimit\":\"8000.00\",\"offeredLimit\":\"6000.00\",\"purchaseApr\":\"22.90\"" \
  && pass "Underwriter approved £6,000; offer at 22.9% APR" || fail "Expected an offer of £6,000: $(echo "$j" | head -c 300)"
curl -sf -X POST "$BFF/v1/credit-cards/applications/$APP/accept" -H 'Content-Type: application/json' -H "$ASHA_H" -d '{}' >/dev/null
j=$(cc_wait "$ASHA_H" '"accounts":\[\{"accountId":"thr_cca_' 30)
ACC2=$(from_key "$j" accounts | field accountId)
[ -n "$ACC2" ] && pass "Asha's credit card opened ($ACC2)" || fail "Asha's credit card did not open"
curl -sf -X POST "$BFF/v1/credit-cards/$ACC2/withdraw" -H "$ASHA_H" >/dev/null
j=$(cc_wait "$ASHA_H" '"status":"WITHDRAWN"' 20)
from_key "$j" accounts | grep -q '"status":"WITHDRAWN"' && pass "Withdrawn within 14 days: Thredd account closed" || fail "Withdrawal did not complete"
APP=$(cc_apply "$ASHA_H" '{"name":"Decline Test","annualIncome":"30000","employmentStatus":"EMPLOYED","requestedLimit":"500","consentToSoftSearch":true}')
j=$(cc_wait "$ASHA_H" "\"applicationId\":\"$APP\",\"status\":\"DECLINED\"" 30)
echo "$j" | grep -q "\"applicationId\":\"$APP\",\"status\":\"DECLINED\"[^}]*\"reasons\":\"BUREAU_ADVERSE\"" && pass "Declined by the decision engine (BUREAU_ADVERSE)" \
  || fail "Expected DECLINED BUREAU_ADVERSE: $(echo "$j" | head -c 300)"
}
