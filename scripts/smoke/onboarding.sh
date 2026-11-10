#!/usr/bin/env bash
# Smoke test for the onboarding domain. Onboarding and KYC: sign-up through the onboarding saga, and a KYC rejection.
# Sourced by scripts/smoke.sh (run: scripts/smoke.sh onboarding).
smoke_onboarding() {
fx_asha
step "Onboarding: a KYC rejection ends with the customer REJECTED"
onb=$(curl -sf -X POST "$BFF/v1/onboarding" -H 'Content-Type: application/json' -H "$ASHA_H0" -H "Idempotency-Key: $(uuid)" \
  -d '{"customerType":"INDIVIDUAL","name":"Reject Test","email":"reject@example.com"}')
ONB2=$(echo "$onb" | field onboardingId)
for _ in $(seq 1 30); do
  st=$(curl -s "$BFF/v1/onboarding/$ONB2" -H "$ASHA_H0"); state=$(echo "$st" | field status)
  [ "$state" = "REJECTED" ] || [ "$state" = "COMPLETED" ] || [ "$state" = "FAILED" ] && break
  sleep 2
done
[ "$state" = "REJECTED" ] && pass "Onboarding REJECTED as expected" || fail "Expected REJECTED, got '$state': $st"
}
