#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────
# zentryx external-monitor · canary for zentryx-status
# ─────────────────────────────────────────────────────────────────
# Runs every 60s via systemd timer. Pings the status service,
# tracks consecutive failures via a tiny state file, posts to Discord
# when the threshold is crossed, and re-alerts at 30min intervals
# until recovery.
#
# Required env (loaded by systemd EnvironmentFile):
#   EXTERNAL_MONITOR_WEBHOOK   Discord webhook (NOT inside .env.sealed)
#   TARGET_URL                 Where to GET (default: status /actuator/health)
# ─────────────────────────────────────────────────────────────────

set -euo pipefail

WEBHOOK="${EXTERNAL_MONITOR_WEBHOOK:?EXTERNAL_MONITOR_WEBHOOK is required}"
TARGET="${TARGET_URL:-http://100.97.248.61:8090/actuator/health}"
STATE_DIR="${STATE_DIR:-/var/lib/zentryx-external-monitor}"
THRESHOLD_FAILS=5      # = 5 minutes of failure before first alert
RE_ALERT_MIN=30        # re-alert every 30 minutes while still down

mkdir -p "${STATE_DIR}"
FAIL_FILE="${STATE_DIR}/fails"
LAST_ALERT_FILE="${STATE_DIR}/last_alert"

# ── Probe ────────────────────────────────────────────────────────
HTTP_CODE="$(curl -sf -o /dev/null -w '%{http_code}' --max-time 8 "${TARGET}" || echo 000)"

if [[ "${HTTP_CODE}" == "200" ]]; then
  # Recovered: clear state, send recovery if we had been alerting
  if [[ -f "${FAIL_FILE}" ]]; then
    PREV_FAILS="$(cat "${FAIL_FILE}" 2>/dev/null || echo 0)"
    rm -f "${FAIL_FILE}" "${LAST_ALERT_FILE}"
    if [[ "${PREV_FAILS}" -ge "${THRESHOLD_FAILS}" ]]; then
      curl -fsS -X POST -H 'Content-Type: application/json' \
        --data "{\"username\":\"Zentryx Canary\",\"embeds\":[{\"title\":\"🟢 zentryx-status RECOVERED\",\"description\":\"Service is responding 200 again.\",\"color\":4905552}]}" \
        "${WEBHOOK}" >/dev/null
    fi
  fi
  exit 0
fi

# ── Fail path ────────────────────────────────────────────────────
PREV_FAILS="$(cat "${FAIL_FILE}" 2>/dev/null || echo 0)"
NEW_FAILS=$(( PREV_FAILS + 1 ))
echo "${NEW_FAILS}" > "${FAIL_FILE}"

# Below threshold: just count, no alert yet
if [[ "${NEW_FAILS}" -lt "${THRESHOLD_FAILS}" ]]; then
  exit 0
fi

# Threshold reached: should we alert?
NOW="$(date +%s)"
LAST_ALERT="$(cat "${LAST_ALERT_FILE}" 2>/dev/null || echo 0)"
ELAPSED_MIN=$(( (NOW - LAST_ALERT) / 60 ))

# First alert when threshold crossed, then every RE_ALERT_MIN minutes
if [[ "${LAST_ALERT}" -eq 0 ]] || [[ "${ELAPSED_MIN}" -ge "${RE_ALERT_MIN}" ]]; then
  MINUTES_DOWN=$(( NEW_FAILS ))
  curl -fsS -X POST -H 'Content-Type: application/json' \
    --data "{
      \"username\":\"Zentryx Canary\",
      \"embeds\":[{
        \"title\":\"🚨 zentryx-status DOWN\",
        \"description\":\"No 200 from ${TARGET} for ~${MINUTES_DOWN} minutes (HTTP ${HTTP_CODE}).\\n\\nLikely needs manual TOTP unseal:\\n\\\`\\\`\\\`\\nssh zentryx-web && cd /opt/zentryx/status && ./deploy.sh\\n\\\`\\\`\\\`\",
        \"color\":15548997
      }]
    }" \
    "${WEBHOOK}" >/dev/null
  echo "${NOW}" > "${LAST_ALERT_FILE}"
fi
