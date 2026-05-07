# external-monitor

> Watches `zentryx-status` from outside its own process. Runs on
> `zentryx-web` (different host), pings the status service every
> minute, and posts a Discord alert if it stops responding for >5min.

## Why this exists

zentryx-status uses enterprise-mode sealed-env: secrets are sealed
behind a TOTP-bound JWS token. When the JVM restarts (OOM, host
reboot, Docker daemon update, anything), the token is gone and the
service crash-loops until a human runs `deploy.sh` with a fresh
TOTP code.

Catch-22: if the in-app `AlertDispatcher` is what would tell you
the service is down, but `AlertDispatcher` itself can't fire
because it can't read the Discord webhook from the locked secrets,
then you'll never know.

This script breaks the cycle. It runs **outside** the zentryx-status
container, has its OWN Discord webhook (in a plain env var, not
sealed), and it's the canary that says "hey, your status service
needs a manual unseal."

## Install on zentryx-web

```bash
ssh zentryx-web

sudo mkdir -p /opt/zentryx/external-monitor
sudo install -m 700 monitor.sh /opt/zentryx/external-monitor/

# Discord webhook lives here in plaintext — chmod 600, root-owned.
# This is a different webhook than the one inside .env.sealed
# (good practice: the canary alert channel is separate from the
# normal alert channel).
sudo tee /etc/zentryx/external-monitor.env >/dev/null <<EOF
EXTERNAL_MONITOR_WEBHOOK=https://discord.com/api/webhooks/...
TARGET_URL=http://100.97.248.61:8090/actuator/health
EOF
sudo chmod 600 /etc/zentryx/external-monitor.env
sudo chown root:root /etc/zentryx/external-monitor.env

# Add a systemd timer to run it every 60s
sudo tee /etc/systemd/system/zentryx-external-monitor.service >/dev/null <<'UNIT'
[Unit]
Description=Zentryx external monitor (canary for status service)
After=network.target

[Service]
Type=oneshot
EnvironmentFile=/etc/zentryx/external-monitor.env
ExecStart=/opt/zentryx/external-monitor/monitor.sh
UNIT

sudo tee /etc/systemd/system/zentryx-external-monitor.timer >/dev/null <<'TIMER'
[Unit]
Description=Run zentryx external monitor every 60s

[Timer]
OnBootSec=60
OnUnitActiveSec=60
Unit=zentryx-external-monitor.service

[Install]
WantedBy=timers.target
TIMER

sudo systemctl daemon-reload
sudo systemctl enable --now zentryx-external-monitor.timer
```

## What it alerts

After 5 consecutive failures (= 5 minutes of downtime), it posts:

```
🚨 zentryx-status DOWN
  No response from /actuator/health for 5+ minutes.
  Likely needs manual TOTP unseal:
  ssh zentryx-web && cd /opt/zentryx/status && ./deploy.sh
```

Then re-alerts every 30 minutes until the service recovers (no spam,
no silence).
