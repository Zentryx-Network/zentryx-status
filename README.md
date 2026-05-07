# zentryx-status

> Public status page service for Zentryx Network. Polls every critical
> endpoint, persists checks in H2, exposes a small REST API, alerts on
> state changes via Discord.

Built specifically as a **production dogfood** for [`sealed-env`](https://github.com/davidalmeidac/sealed-env)
in **enterprise mode** — secrets are sealed behind a TOTP-bound JWS
unseal token. Every deploy requires a fresh 6-digit code from the
operator's authenticator app; the token is bound to a specific commit
SHA and expires in 60 seconds.

If the JVM restarts (OOM, host reboot, etc.) the token is gone and
the service crash-loops until a human re-runs `deploy.sh`. By design:
secrets never re-decrypt without explicit human approval.

## Threat model

| Attacker has... | Can they decrypt secrets? |
|---|---|
| `.env.sealed` only | ❌ — needs master key |
| Master key only | ❌ — needs current TOTP code |
| TOTP secret (base32) only | ❌ — needs master key + signing key |
| Stolen unseal token from CI logs | ❌ — bound to deploy_id, expired in 60s |
| Master key + TOTP secret + signing key | ✅ — but that's 3 separate exfils |
| Container memory dump while running | ✅ — same as any service, mitigated by host hardening |

## What it monitors (out of the box)

| Target | URL | Expects |
|---|---|---|
| `zentryxnet-web` | https://zentryxnet.lat/health | 200 |
| `zentryxnet-blog` | https://zentryxnet.lat/blog | 200 |
| `speedtest-ping` | https://speed.zentryxnet.lat/ping | 204 |
| `mail-https` | https://mail.zentryxnet.lat/ | 200 |

Edit `application.yml → zentryx.status.targets` to add/remove. Every
target gets one HTTP probe every 30s.

## Public API

| Endpoint | Description |
|---|---|
| `GET /api/v1/status` | Current snapshot — last known state per target |
| `GET /api/v1/uptime?days=7` | Uptime % per target over N days (1-90) |
| `GET /api/v1/history?target=X&hours=24` | Raw checks for a single target |

All three are public and CORS-friendly — embed them in dashboards,
hit them from `curl`, scrape them with a bot.

## Local development (basic mode)

For day-to-day dev you don't want to type a TOTP every restart. Use
basic mode locally:

```bash
# 1. Switch to basic mode in dev
sealed-env init                                   # generates master key in .env.local
cp .env.example src/main/resources/.env
# edit src/main/resources/.env with real values

sealed-env encrypt src/main/resources/.env \
  -o src/main/resources/.env.sealed

# 2. application.yml: temporarily set sealed-env.mode=basic for dev
# 3. Run
mvn spring-boot:run
```

Production stays in enterprise mode regardless.

## Production deploy (enterprise mode)

### One-time setup on `zentryx-web`

```bash
ssh zentryx-web

# 1. Install sealed-env CLI
sudo npm i -g sealed-env

# 2. Clone the repo
sudo mkdir -p /opt/zentryx
sudo git clone https://github.com/Zentryx-Network/zentryx-status.git \
  /opt/zentryx/status
cd /opt/zentryx/status

# 3. Drop the master key + signing key + TOTP secret
#    These were created by `sealed-env init --mode enterprise`
#    on YOUR LAPTOP — copy that .env.local here.
sudo install -m 600 -o root -g root /tmp/sealed.env.local .env.local
rm /tmp/sealed.env.local
```

### Every deploy

```bash
ssh zentryx-web
cd /opt/zentryx/status
git pull --ff-only
./deploy.sh
# It asks for a 6-digit TOTP, mints a 60s token bound to the new
# commit, builds Docker, starts container, tails logs, verifies
# health. Total time ~25-40 seconds.
```

The deploy script refuses to run on a dirty working tree — your
working copy must be clean and the code that ships matches the
deploy_id the token is bound to.

### External monitor (mandatory)

Because zentryx-status uses fail-safe crashloops on bad/expired
tokens, its own AlertDispatcher can't tell you when it's down. The
companion [`external-monitor/`](./external-monitor/) lives on
zentryx-web (different host), runs every 60s via systemd timer,
and posts to Discord after 5min of consecutive failures.

The Discord webhook for the canary is **NOT** inside `.env.sealed`
— it's in plaintext at `/etc/zentryx/external-monitor.env` (chmod
600, root-owned). Different threat model: this webhook is the one
piece that keeps working when the sealed pipeline fails.

See [`external-monitor/README.md`](./external-monitor/README.md) for
install instructions.

## How it differs from a generic Spring Boot service

- **`sealed-env-spring-boot-starter` in enterprise mode**: every
  deploy requires a fresh TOTP code from the operator. Token is
  bound to commit SHA; expires in 60 seconds. Same encrypted file
  format works in Node ([news-scout](https://github.com/Zentryx-Network/news-scout))
  and Spring Boot.
- **Strict timeouts on every outbound request**: 4s connect + 8s
  read. A frozen target never holds up the next polling cycle.
- **Append-only schema**: `health_check` is INSERT-only, no
  UPDATEs. Daily prune drops anything older than 30 days.
- **Cooldown on alerts**: a flapping target generates one Discord
  ping every 15 minutes max, not one every 30 seconds.
- **Bound to 127.0.0.1**: Spring app only listens on localhost.
  External access goes through Caddy/nginx, which terminates TLS.
- **External canary**: a separate process (different host, different
  webhook, no shared deps) tells you when this service goes silent.

## License

MIT. See [LICENSE](./LICENSE).

---

Built by [Zentryx Network](https://zentryxnet.lat). Powered by
[sealed-env](https://github.com/davidalmeidac/sealed-env) (enterprise mode).
