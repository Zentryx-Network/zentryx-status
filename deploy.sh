#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────
# zentryx-status · enterprise-mode deploy script
# ─────────────────────────────────────────────────────────────────
# Run this on the deploy host (zentryx-web via Tailscale). It:
#
#   1. Reads the current commit sha from `git rev-parse HEAD` and
#      uses it as deploy_id — the unseal token will be bound to it
#   2. Asks the operator for the 6-digit TOTP code from their
#      Google Authenticator / Authy / 1Password
#   3. Mints a 60-second JWS via `sealed-env unseal`
#   4. Builds the Docker image (if needed) and starts the container
#      with the token + deploy_id injected as env vars
#   5. Tails the startup logs so the operator can confirm the
#      service unsealed successfully (look for "Started ApplicationContext")
#
# If the token expires before the JVM finishes booting, the deploy
# fails — re-run with a fresh code. By design: secrets never live
# longer than necessary.
#
# Requirements on the host:
#   • sealed-env CLI installed globally (npm i -g sealed-env)
#   • The master key + signing key + TOTP secret in ./.env.local
#     (or wherever sealed-env is configured to look — see
#     `sealed-env config show`)
#   • git, docker, docker compose
# ─────────────────────────────────────────────────────────────────

set -euo pipefail

# ── Pre-flight ───────────────────────────────────────────────────
command -v git >/dev/null         || { echo "✗ git not installed";        exit 1; }
command -v docker >/dev/null      || { echo "✗ docker not installed";     exit 1; }
command -v sealed-env >/dev/null  || { echo "✗ sealed-env CLI not installed (npm i -g sealed-env)"; exit 1; }

if [[ ! -f docker-compose.yml ]]; then
  echo "✗ run this from the repo root (docker-compose.yml not found here)"
  exit 1
fi

if [[ ! -f src/main/resources/.env.sealed ]]; then
  echo "✗ src/main/resources/.env.sealed missing — seal it first:"
  echo "    sealed-env encrypt src/main/resources/.env --mode enterprise \\"
  echo "      -o src/main/resources/.env.sealed"
  exit 1
fi

# ── Resolve deploy_id ────────────────────────────────────────────
DEPLOY_ID="$(git rev-parse HEAD)"
DEPLOY_ID_SHORT="${DEPLOY_ID:0:7}"

# Refuse to deploy with a dirty tree — the deploy_id binds to the
# committed sha, so uncommitted changes would silently NOT be in
# the build. Better to fail loud than ship a stale deploy.
if ! git diff-index --quiet HEAD --; then
  echo "✗ working tree is dirty. Commit or stash before deploying."
  echo "  Files changed:"
  git status --short
  exit 1
fi

cat <<EOF

  ┌──────────────────────────────────────────────────────────┐
  │  zentryx-status · enterprise deploy                      │
  ├──────────────────────────────────────────────────────────┤
  │  branch:     $(git rev-parse --abbrev-ref HEAD)
  │  commit:     ${DEPLOY_ID_SHORT}
  │  message:    $(git log -1 --pretty=%s | head -c 50)
  └──────────────────────────────────────────────────────────┘

EOF

# ── TOTP prompt ──────────────────────────────────────────────────
read -r -p "  TOTP code (6 digits, no spaces): " TOTP_CODE
echo

if [[ ! "${TOTP_CODE}" =~ ^[0-9]{6}$ ]]; then
  echo "✗ invalid TOTP — must be exactly 6 digits"
  exit 1
fi

# ── Mint the unseal token ────────────────────────────────────────
echo "▸ Minting unseal token (TTL 60s, bound to ${DEPLOY_ID_SHORT})..."
SEALED_ENV_UNSEAL_TOKEN="$(sealed-env unseal \
  --file src/main/resources/.env.sealed \
  --totp "${TOTP_CODE}" \
  --deploy-id "${DEPLOY_ID}" \
  --ttl 60)"

if [[ -z "${SEALED_ENV_UNSEAL_TOKEN}" ]]; then
  echo "✗ unseal failed — check TOTP code and try again"
  exit 1
fi

export SEALED_ENV_UNSEAL_TOKEN
export SEALED_ENV_DEPLOY_ID="${DEPLOY_ID}"

# ── Build + run ──────────────────────────────────────────────────
echo "▸ Building image..."
docker compose build --quiet status

echo "▸ Starting container with unseal token..."
docker compose up -d status

# ── Tail startup logs ────────────────────────────────────────────
echo "▸ Watching logs for 25s (Ctrl+C to detach early)..."
echo
( docker compose logs -f --tail=0 status & ) > /tmp/zsdeploy.log 2>&1 &
LOGS_PID=$!
sleep 25
kill "${LOGS_PID}" 2>/dev/null || true

# ── Verify health ────────────────────────────────────────────────
echo
echo "▸ Health check..."
if curl -sf http://127.0.0.1:8090/actuator/health >/dev/null; then
  echo "✓ zentryx-status is UP (deploy ${DEPLOY_ID_SHORT})"
  echo "  Status JSON: curl http://127.0.0.1:8090/api/v1/status"
else
  echo "✗ health check failed — service didn't unseal in time?"
  echo "  Check logs: docker compose logs --tail 100 status"
  echo "  If 'unseal token expired', re-run this script (token TTL is 60s)"
  exit 1
fi
