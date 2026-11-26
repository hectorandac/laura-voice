#!/bin/sh
# Stops k3s-agent if the VPN daemon spins (default: zerotier-one above 80% CPU for 60 s), so a routing loop
# through the VPN can't wedge the node. Also drops any route to SERVER_LAN_IP via the VPN interface.
# Env: VPN_PROC (zerotier-one), VPN_IF (VPN interface), SERVER_LAN_IP, LIMIT (80), WINDOW (60), STEP (5).
set -u
VPN_PROC=${VPN_PROC:-zerotier-one}
LIMIT=${LIMIT:-80}
WINDOW=${WINDOW:-60}
STEP=${STEP:-5}
HZ=$(getconf CLK_TCK)

ticks() {
  pid=$(pgrep -o -x "$VPN_PROC") || { echo 0; return; }
  awk '{print $14 + $15}' "/proc/$pid/stat" 2>/dev/null || echo 0
}

hot=0
prev=$(ticks)
while true; do
  sleep "$STEP"
  cur=$(ticks)
  pct=$(( (cur - prev) * 100 / (HZ * STEP) ))
  prev=$cur
  if [ "$pct" -ge "$LIMIT" ]; then hot=$((hot + STEP)); else hot=0; fi
  if [ "$hot" -ge "$WINDOW" ]; then
    logger -t vpn-watchdog "$VPN_PROC at ${pct}% CPU for ${WINDOW}s: stopping k3s-agent"
    systemctl stop k3s-agent
    if [ -n "${SERVER_LAN_IP:-}" ] && [ -n "${VPN_IF:-}" ]; then
      ip route del "$SERVER_LAN_IP" dev "$VPN_IF" 2>/dev/null && logger -t vpn-watchdog "removed route to $SERVER_LAN_IP via $VPN_IF"
    fi
    hot=0
  fi
done
