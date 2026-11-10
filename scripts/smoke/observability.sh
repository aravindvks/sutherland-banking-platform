#!/usr/bin/env bash
# Smoke test for the observability domain. Observability: one trace per payment and card tap, metrics, alert rules, logs.
# Sourced by scripts/smoke.sh (run: scripts/smoke.sh observability).
smoke_observability() {
fx_payment
fx_tap
# Service names in one Tempo trace, space separated.
trace_services() { curl -s "$TEMPO/api/traces/$1" | { grep -o '"key":"service.name","value":{"stringValue":"[^"]*"' || true; } \
  | sed 's/.*"stringValue":"\(.*\)"/\1/' | sort -u | tr '\n' ' '; }
# Trace IDs matching a TraceQL query in the last hour.
traces() { curl -s -G "$TEMPO/api/search" --data-urlencode "q=$1" --data-urlencode "limit=20" \
  --data-urlencode "start=$(( $(date +%s) - 3600 ))" --data-urlencode "end=$(( $(date +%s) + 60 ))" \
  | { grep -o '"traceID":"[0-9a-f]*"' || true; } | sed 's/.*:"\(.*\)"/\1/'; }
# First value of an instant Prometheus query, or empty.
promq() { curl -s -G "$PROM/api/v1/query" --data-urlencode "query=$1" \
  | { grep -o '"value":\[[^]]*\]' || true; } | head -1 | sed 's/.*,"\(.*\)"\]/\1/'; }
has_all() { for s in $2; do echo " $1 " | grep -q " $s " || return 1; done; }

# With Prometheus up the observability stack is running, so Tempo and Loki must answer too (they take ~20 s to be ready).
if curl -sf "$PROM/-/ready" >/dev/null 2>&1; then
  for _ in $(seq 1 30); do curl -sf "$TEMPO/ready" >/dev/null 2>&1 && curl -sf "$LOKI/ready" >/dev/null 2>&1 && break; sleep 2; done
  curl -sf "$TEMPO/ready" >/dev/null 2>&1 || fail "Prometheus is up but Tempo is not ready at $TEMPO (docker compose logs tempo)"
  curl -sf "$LOKI/ready" >/dev/null 2>&1 || fail "Prometheus is up but Loki is not ready at $LOKI (docker compose logs loki)"
fi
if curl -sf "$TEMPO/ready" >/dev/null 2>&1; then
  step "Tracing: Priya's £25 payment is one trace from the BFF through Kafka and Temporal to the Essence adapter"
  want="bff orchestrator essence-adapter"; found=""; seen=""
  for _ in $(seq 1 30); do
    for t in $(traces "{ span.pbp.correlation_id = \"$CORR\" }"); do
      svc=$(trace_services "$t"); seen="$seen [$t: $svc]"
      has_all "$svc" "$want" && { found="$t ($svc)"; break 2; }
    done
    sleep 2
  done
  [ -n "$found" ] && pass "Trace $found" \
    || fail "No single trace for $CORR spans $want. Traces seen:${seen:- none}. Check traceparent in the outbox and the Temporal interceptors"

  step "Tracing: a card tap is one trace from the EHI responder to the Essence hold"
  found=""
  for _ in $(seq 1 15); do
    # The EHI request span and the Essence hold span in one trace (not, say, a reconciliation run that calls both).
    t=$(traces '{ resource.service.name = "ehi-responder" && span.uri = "/ehi/v1/messages" } && { resource.service.name = "essence-adapter" && span.uri =~ ".*holds.*" }' | head -1)
    [ -n "$t" ] && { found="$t ($(trace_services "$t"))"; break; }
    sleep 2
  done
  [ -n "$found" ] && pass "Trace $found" || fail "No trace joins ehi-responder and essence-adapter"
else
  echo "  SKIP Tempo not reachable at $TEMPO (observability stack not running)"
fi

if curl -sf "$PROM/-/ready" >/dev/null 2>&1; then
  step "Metrics, alerts and logs: every service scraped, flow metrics recorded, alert rules loaded, logs searchable"
  # Pick up a changed scrape config (a new service) without restarting Prometheus.
  curl -s -X POST "$PROM/-/reload" >/dev/null 2>&1 || true
  up=""; done_n=""; ehi_n=""
  for _ in $(seq 1 30); do
    up=$(promq 'count(up{job="pbp"} == 1)'); done_n=$(promq 'sum(pbp_payment_transitions_total{status="COMPLETED"})')
    ehi_n=$(promq 'sum(pbp_ehi_decision_seconds_count)')
    [ "$up" = 10 ] && [ -n "$done_n" ] && [ -n "$ehi_n" ] && break
    sleep 3
  done
  [ "$up" = 10 ] && pass "Prometheus scrapes all 10 services" || fail "Prometheus sees ${up:-0} of 10 services up; scraped: $(curl -s -G "$PROM/api/v1/query" --data-urlencode 'query=up{job="pbp"}' | grep -o '"service":"[^"]*"[^}]*}[^]]*"[01]"' | sed 's/"service":"\([^"]*\)".*"\([01]\)"/\1=\2/' | tr '\n' ' '). If credit-adapter is missing, Prometheus has an old config: docker compose -f infra/compose/docker-compose.yml restart prometheus"
  [ -n "$done_n" ] && pass "Payment flow metrics: $done_n status changes to COMPLETED counted" \
    || fail "pbp_payment_transitions_total has no COMPLETED series (FlowMetricsCollector in the orchestrator)"
  [ -n "$ehi_n" ] && pass "Card authorisation timer: $ehi_n decisions recorded" || fail "pbp_ehi_decision_seconds has no samples"
  rules=$(curl -s "$PROM/api/v1/rules")
  echo "$rules" | grep -q '"name":"EhiOverTimeBudget"' && echo "$rules" | grep -q '"name":"StandingOrderMissed"' \
    && pass "Alert rules loaded ($(echo "$rules" | grep -o '"type":"alerting"' | wc -l | tr -d ' ') rules)" || fail "Alert rules not loaded: ${rules:0:300}"
  if curl -sf "$LOKI/ready" >/dev/null 2>&1; then
    lines=""
    for _ in $(seq 1 20); do
      lines=$(curl -s -G "$LOKI/loki/api/v1/query_range" --data-urlencode "query={service=\"orchestrator\"} |= \"cid=$CORR\"" \
        --data-urlencode "limit=5" --data-urlencode "start=$(( $(date +%s) - 3600 ))000000000" | { grep -o '"values":\[\[' || true; })
      [ -n "$lines" ] && break
      sleep 3
    done
    [ -n "$lines" ] && pass "Loki finds the orchestrator's log lines for $CORR" || fail "No orchestrator log lines in Loki for cid=$CORR"
  fi
else
  echo "  SKIP Prometheus not reachable at $PROM (observability stack not running)"
fi
}
