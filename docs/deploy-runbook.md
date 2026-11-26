# Runbook: GPU laptop node and cutover to the cluster

Moves the voice stack from three hand-started processes on the laptop to the Helm release, with the laptop
(re)joined to k3s as a GPU agent. **Phase 1** is the voice stack only; the Hermes link stays off (no
`LAURA_LINK_TOKEN`), so the gateway behaves exactly like the hand-started one. **Phase 2** turns on Laura's
Hermes channel.

Run each step only after the previous one verifies. **[server]** = control-plane host, **[laptop]** = GPU node.
Times are wall-clock estimates; downtime only happens in step 7.

## 0. Variables

```sh
LAPTOP=user@<laptop VPN IP>  NODE=<node name>  LAPTOP_IP=<laptop VPN IP>
SERVER_LAN_IP=<server LAN IP>  VPN_IF=<VPN interface on laptop>
VALUES=<private values.yaml>  CHART=deploy/helm/laura-voice  REGISTRY=<registry>/laura-voice
```

The reference cluster's values are in `runbook.env`, next to the private Helm values.

## Go / no-go (before step 1)

| Check | Command or how | Go if |
|---|---|---|
| Owner present and agrees to the node rejoin | ask | explicit yes |
| Laptop on AC, home LAN, sleep inhibited during the work | `systemd-inhibit --what=sleep:idle sleep 3h &` on the laptop | yes |
| Cluster healthy | `kubectl get nodes; kubectl get pods -A \| grep -vE 'Running\|Completed'` | server Ready, nothing failing |
| Images built from the commit being deployed | step 1 | `gateway:<tag>` in the registry |
| GPU lock free | `cat ~/llm/voice/GPU_LOCK` on the laptop | absent |
| Route pre-check clean | step 2 | `clean` |

**No-go / abort triggers at any point:** VPN daemon above 20% CPU for over a minute after the agent starts; the
node not Ready within 3 minutes; any pod in CrashLoopBackOff; GPU memory above 7.5 GB; first reply audio above
2 s; the phone can't connect. On abort, use that step's rollback.

## Phase 1

### 1. Build the gateway image from the deployed commit [server], ~5 min, no downtime

```sh
git -C <repo> log --oneline -1                      # note the commit
REGISTRY=$REGISTRY deploy/build.sh 0.2.0 gateway    # deps layer is cached; only the code layers change
```

Verify: `docker run --rm --network none $REGISTRY/gateway:0.2.0 python -c "import assistant_server, asr, speaker, addressee"`
exits 0. The llama-server and tts images (`0.1.0`, `0.1.1`) are unchanged.
Rollback: none needed. The old tag stays in the registry.

### 2. Rejoin the node safely [laptop], ~15 min including a 10 min soak

**Never route `SERVER_LAN_IP` through the VPN interface.** A route like that captures the VPN daemon's own
underlay packets to the server: an encapsulation loop that pins zerotier-one at 100% CPU and takes the laptop
offline. It happened once. The trade-off of not having the route: while the laptop is away from the home LAN,
`kubectl logs/exec` to it fails, but the node stays Ready and the voice stack works.

**Pre-check. Abort on any output:**

```sh
found=$(ssh $LAPTOP "sudo grep -rn -F '$SERVER_LAN_IP' /etc/systemd/system/k3s-agent.service.d/ /etc/rancher/k3s/config.yaml 2>/dev/null; \
  ip route show table all | grep -F '$SERVER_LAN_IP'")
[ -n "$found" ] && echo "ABORT: $found" || echo clean
```

To clean up: delete the line from the drop-in (it must contain only the wait below), `sudo ip route del $SERVER_LAN_IP`,
then run the pre-check again.

**Watchdog first.** It stops k3s-agent if the VPN daemon stays above 80% CPU for 60 s, and drops any route to
the server via the VPN:

```sh
scp deploy/node/vpn-watchdog.sh deploy/node/vpn-watchdog.service $LAPTOP:/tmp/
ssh $LAPTOP "sudo install -m 755 /tmp/vpn-watchdog.sh /usr/local/sbin/vpn-watchdog.sh && \
  sudo install -m 644 /tmp/vpn-watchdog.service /etc/systemd/system/ && \
  printf 'SERVER_LAN_IP=$SERVER_LAN_IP\nVPN_IF=$VPN_IF\n' | sudo tee /etc/default/vpn-watchdog >/dev/null && \
  sudo systemctl daemon-reload && sudo systemctl enable --now vpn-watchdog && systemctl is-active vpn-watchdog"
```

**The drop-in** `/etc/systemd/system/k3s-agent.service.d/zerotier.conf` has exactly this content:

```ini
[Unit]
Wants=zerotier-one.service
After=zerotier-one.service

[Service]
ExecStartPre=/bin/sh -c 'for i in $(seq 1 90); do ip -4 addr show <VPN_IF> 2>/dev/null | grep -q inet && exit 0; sleep 2; done; exit 0'
```

**Start:** `ssh $LAPTOP "sudo systemctl unmask k3s-agent && sudo systemctl daemon-reload && sudo systemctl enable --now k3s-agent"`

Verify, then watch for 10 minutes:

```sh
kubectl get node $NODE                                                        # Ready within ~1 min
kubectl get node $NODE -o jsonpath='{.status.allocatable.nvidia\.com/gpu}'    # 4
kubectl get pods -A -o wide --field-selector spec.nodeName=$NODE              # nvidia-device-plugin only
ssh $LAPTOP "ip route show table all | grep -F '$SERVER_LAN_IP'; top -bn1 -p \$(pgrep -x zerotier-one) | tail -1"
                                                                              # no route; zerotier ~0-5% CPU
```

The hand-started voice stack keeps running throughout; the node runs no voice pods yet.
Rollback: `ssh $LAPTOP "sudo systemctl disable --now k3s-agent && sudo systemctl mask k3s-agent"`. The watchdog
can stay installed.

### 3. Pre-pull images [laptop], ~10-15 min, no downtime

About 15 GB unpacked (gateway 8.5, llama-server 3.6, tts 2.8, plus nginx). Pulling now keeps it out of the downtime window.

```sh
for i in gateway:0.2.0 llama-server:0.1.0 tts:0.1.1; do ssh $LAPTOP "sudo k3s crictl pull $REGISTRY/$i"; done
ssh $LAPTOP "sudo k3s crictl pull docker.io/library/nginx:1.27-alpine"
```

Verify: `ssh $LAPTOP "sudo k3s crictl images | grep -E 'laura-voice|nginx'"` lists all four.
Rollback: `sudo k3s crictl rmi ...` (optional).

### 4. Record the live stack for rollback [laptop], ~5 min, no downtime

Take the GPU lock (`~/llm/voice/GPU_LOCK`: owner, time, "cutover to k3s"). Save each process's command line and
working directory, and the **names** of its environment variables (values only for non-secret tuning such as
`SPEAKER_THRESHOLD`, `AVATAR_TAGS`, `TORCH_THREADS`, `ASSISTANT_DATA`):

```sh
ssh $LAPTOP 'for p in $(pgrep -f "llama-server --models-preset|uvicorn tts_server|uvicorn assistant_server"); do
  echo "== $p $(readlink /proc/$p/cwd)"; tr "\0" " " < /proc/$p/cmdline; echo;
  tr "\0" "\n" < /proc/$p/environ | grep -E "^(ASSISTANT_|SPEAKER_|AVATAR_|TORCH_|LLM_|TTS_|TMPDIR|HF_)" | grep -v ASSISTANT_KEY; done' \
  > live-stack-$(date +%F).txt
```

Copy any tuning variables that differ from the chart defaults into `gateway.env` in the private values.
Verify: the file lists 3 processes. Rollback: nothing to undo.

### 5. Seed caches and the voice data [laptop], ~3 min, no downtime

The gateway runs as uid 10001. `ASSISTANT_DATA` holds the enrolled voice profile and the recall log; use the live
process's `ASSISTANT_DATA` from step 4 (default `~/.local/share/laura-voice`). Don't enroll during this step.

```sh
ssh $LAPTOP 'D=${ASSISTANT_DATA:-$HOME/.local/share/laura-voice}; set -e
  sudo mkdir -p /var/lib/laura-voice/data /var/lib/laura-voice/cache/gateway /var/lib/laura-voice/cache/supertonic
  sudo cp -a "$D"/. /var/lib/laura-voice/data/
  sudo cp -a ~/.cache/huggingface /var/lib/laura-voice/cache/gateway/hf
  [ -d ~/.cache/torch/NeMo ] && sudo cp -a ~/.cache/torch/NeMo /var/lib/laura-voice/cache/gateway/nemo
  sudo cp -a ~/.cache/supertonic3 /var/lib/laura-voice/cache/supertonic/
  sudo chown -R 10001:10001 /var/lib/laura-voice; sudo ls -la /var/lib/laura-voice/data; du -sh /var/lib/laura-voice/cache/*'
```

Verify: `voice_profile.npz` is present (if Hector enrolled), the gateway cache is ~2.4 GB, supertonic ~390 MB.
Keep the original `$D` untouched: it is the rollback copy and the backup.
Rollback: `sudo rm -rf /var/lib/laura-voice`.

### 6. Secrets [server], ~2 min, no downtime (the owner runs it if automation may not handle secrets)

`create-secrets.sh` (next to the private values) **without** `--with-link`. It creates `voice/laura-voice`
(the existing `ASSISTANT_KEY`) and `voice/gateway-legacy-tls` (the certificate the phone already accepts). It
prints no secret values.

Verify: `kubectl -n voice get secret laura-voice gateway-legacy-tls`, and
`kubectl -n voice get secret laura-voice -o jsonpath='{.data}' | grep -c LAURA_LINK_TOKEN` prints `0`.
Rollback: `kubectl delete ns voice`.

### 7. Cutover [laptop + server], downtime ~2-4 min

```sh
ssh $LAPTOP 'pkill -f "uvicorn assistant_server"; pkill -f "uvicorn tts_server"; pkill -f "llama-server --models-preset";
  sleep 3; nvidia-smi --query-gpu=memory.used --format=csv,noheader'               # ~0-300 MiB (desktop)
helm upgrade --install voice $CHART -n voice --reset-values -f $VALUES
kubectl -n voice rollout status deploy/voice-llm deploy/voice-tts deploy/voice-gateway --timeout=6m
```

The gateway's startup probe waits for ASR and the speaker model (CPU load, then GPU): expect 60-120 s.
If the rollout isn't done in 6 minutes, roll back (below) and investigate later.

### 8. Verify [server + phone], ~10 min

```sh
kubectl -n voice get pods -o wide                                         # llm 1/1, tts 1/1, gateway 2/2 on $NODE
ssh $LAPTOP nvidia-smi --query-gpu=memory.used --format=csv,noheader      # ~6.0-6.5 GiB, not above 7.5
curl -sk https://$LAPTOP_IP:8443/health                                   # {"asr": true, ...}
curl -sk -o /dev/null -w '%{http_code}\n' https://$LAPTOP_IP:8443/laura   # 404 (link not exposed)
kubectl -n voice logs deploy/voice-gateway -c gateway | grep -iE 'ASR ready|speaker|error' | tail
WS_URL="wss://$LAPTOP_IP:8443/ws?k=<key>" python server/tests/ws_test.py  # first audio <= ~1.5 s, barge-in OK
```

On the phone:

- One addressed turn gets an answer.
- One unaddressed sentence gets no answer (`ignored`).
- The app shows the speaker profile as enrolled (`speaker_profile.enrolled: true`).
- Talking over a reply stops it.

Then release the GPU lock and watch for 30 minutes (`kubectl -n voice get pods -w`, phone use).

**Rollback for steps 7-8, ~2 min:**
`kubectl -n voice scale deploy --all --replicas=0`. Wait for GPU memory to drop, then start the three processes
again with the command lines from step 4, in their recorded working directories, sourcing `.env` for the
gateway. The data in `$ASSISTANT_DATA` was never moved, so the old process starts with the same profile.

**Total for phase 1:** about 70-90 minutes of work, with about 2-4 minutes of voice downtime (step 7).

## Phase 2: Laura's Hermes channel (separate session)

1. `create-secrets.sh --with-link`: adds `LAURA_LINK_TOKEN` to the voice secret and `LAURA_VOICE_TOKEN` to
   Hermes. `kubectl -n voice rollout restart deploy/voice-gateway` (~2 min downtime; it reloads ASR).
2. Hermes: back up the values (`helm -n hermes get values hermes -o yaml`), then
   `helm upgrade hermes <hermes chart> -n hermes --reset-values -f <hermes chart>/values.yaml`. The chart already
   carries the plugin files, `plugins.enabled`, `LAURA_VOICE_*` and the `laura-voice-local` provider. The Hermes
   pod restarts; Matrix is down for about a minute.
3. Verify:
   - `hermes plugins list` in the pod shows `laura-voice` enabled.
   - The gateway log shows `Hermes linked`.
   - The phone gets `laura_status: connected`.
   - One Matrix message still works.
   - One tiny spoken handoff produces a task card, "On it.", then the result.
   - An approval appears as a card. Never approve by voice.
4. Rollback: `helm -n hermes rollback hermes <previous revision>`, and remove `LAURA_LINK_TOKEN` from the voice
   secret plus restart the gateway (it then runs exactly as in phase 1).

## Later: devices on the private CA

`kubectl -n voice get secret voice-ca -o jsonpath='{.data.ca\.crt}' | base64 -d > laura-voice-ca.crt`. Install it
on each device as a CA certificate, set `tls.existingSecret: ""`, and upgrade. Verify with
`openssl s_client -connect $LAPTOP_IP:8443 -CAfile laura-voice-ca.crt </dev/null | grep 'return code: 0'`.
Rollback: set `tls.existingSecret` back to the legacy secret.
