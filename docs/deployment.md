# Deployment

The reference setup is a k3s cluster whose control plane is a home server, plus a laptop with an NVIDIA GPU
joined as an agent node over ZeroTier. The voice stack is pinned to the laptop. Anything else works too: any
Kubernetes node with an NVIDIA GPU and roughly 7 GB of free VRAM.

## 1. GPU node

**Joining over a VPN.** Run flannel over the VPN interface on every node, so pod traffic works wherever the
laptop happens to be:

| Where | k3s config |
|---|---|
| server | `flannel-iface: <vpn-if>`, `node-ip: <its LAN IP>` (pin it: with `flannel-iface` set and no `node-ip`, k3s moves the node IP to the VPN address), `flannel-conf:` a vxlan net-conf with `"MTU": 1500` |
| laptop agent | `server: https://<server VPN IP>:6443`, `node-ip: <laptop VPN IP>`, `flannel-iface: <vpn-if>`, the same `flannel-conf`, `node-label: [dedicated=voice, gpu=true]`, `node-taint: [dedicated=voice:NoSchedule]` |

Notes from the reference cluster:

- flannel's vxlan `MTU` is the outer MTU; it subtracts 50. `1500` keeps pods at 1450 like a default
  Ethernet setup. Without it, flannel inherits the VPN interface's MTU (2800 on ZeroTier), and new pods get
  larger MTUs than existing ones and than the server's uplink.
- An existing `flannel.1` keeps its old MTU across restarts. Fix it once with `ip link set flannel.1 mtu 1450`.
- Order k3s after the VPN: a systemd drop-in with `After=zerotier-one.service` plus a short wait for the
  interface's address. The control plane then depends on the VPN interface existing at boot. Workloads that are
  already running keep running if it disappears later.
- **Do not route the server's physical (LAN) address through the VPN.** k3s agents learn the API server
  address from the `kubernetes` endpoints, which is the server's LAN IP. A `/32` route to that IP via the
  ZeroTier interface looks like it fixes "laptop away from home". In fact it captures ZeroTier's own UDP
  underlay to the same IP, creating an encapsulation loop that pinned zerotier-one at 100% CPU and took the
  laptop offline. The consequence of not doing it: while the laptop is off the home LAN, the agent's main API
  connection falls back to the configured VPN URL (the node stays Ready), but the reverse tunnel the API server
  uses for `kubectl logs/exec/port-forward` cannot connect, so those commands fail until the laptop is home.
  The voice service itself is unaffected.

**Cluster hygiene for a node that sleeps.** Check every DaemonSet that tolerates all taints (`operator: Exists`).
In the reference cluster the node exporter did, and it would have collided with the laptop's own exporter on
`:9100`, so it got `nodeAffinity: dedicated DoesNotExist`. Scope "node not ready" and "deployment unavailable"
alerts away from the node (`unless on (node) kube_node_spec_taint{key="dedicated", value="voice"}`).

**GPU.** Install nvidia-container-toolkit on the node; k3s detects the runtime and the cluster gets a `nvidia`
RuntimeClass. Install the NVIDIA device plugin with a toleration for the node's taint and time-slicing, so the
three pods share one GPU:

```yaml
tolerations:
  - { key: dedicated, operator: Equal, value: voice, effect: NoSchedule }
config:
  default: default
  map:
    default: |-
      version: v1
      sharing:
        timeSlicing:
          resources:
            - { name: nvidia.com/gpu, replicas: 4 }
```

Time-slicing does not partition memory. The budget on an 8 GB card is about 2.9 GB for ASR, 0.9 GB for TTS and
2.4 GB for the 9B model at 16K context. Every Deployment uses `strategy: Recreate`, because two copies of a
component do not fit on the GPU.

## 2. Images

`deploy/build.sh` builds and pushes `llama-server`, `tts` and `gateway` (`REGISTRY=... deploy/build.sh <tag>`).
The llama.cpp image compiles for one CUDA architecture (`--build-arg CUDA_ARCH=75` for Turing). The gateway
image is large (PyTorch with CUDA plus NeMo).

## 3. Chart

```sh
kubectl create namespace voice
kubectl -n voice create secret generic laura-voice \
  --from-literal=ASSISTANT_KEY=... --from-literal=LAURA_LINK_TOKEN=...
helm upgrade --install voice deploy/helm/laura-voice -n voice --reset-values -f my-values.yaml
```

| Value | Default | Purpose |
|---|---|---|
| `nodeSelector`, `tolerations` | `dedicated: voice` | Pin to the GPU node |
| `tolerateNodeAway` | `true` | Tolerate `unreachable`/`not-ready` forever: when the laptop sleeps, the pods stay bound to it and resume on wake instead of being evicted to nowhere |
| `llm.modelsHostPath` | `/var/lib/laura-voice/models` | Host directory with the `.gguf` files (read-only) |
| `llm.presets` | Bonsai 2 9B, 16K | llama-server router presets (`models.ini`) |
| `llm.allowedNamespaces` | `[]` | Who else may call the LLM (e.g. the Hermes namespace) |
| `gateway.allowedNamespaces` | `[]` | Who may reach the plain in-cluster port (the Hermes plugin) |
| `deviceEndpoint.hostPort` / `hostIP` | `8443` / all | TLS endpoint for devices on the node itself; set `hostIP` to the VPN address to keep it off other networks |
| `tls.privateCA.enabled` | `true` | cert-manager self-signed CA plus a server certificate for `tls.certificate.ipAddresses/dnsNames` |
| `tls.existingSecret` | `""` | Serve your own certificate instead |
| `ingress.enabled` | `false` | Alternative: expose through an ingress controller (add a source allow-list) |

The gateway listens on plain HTTP 8080 inside the pod network. An nginx sidecar terminates TLS on the node's
`hostPort`, so phones reach the laptop directly (no hairpin through the server), and it refuses `/laura`
from outside the cluster.

## 4. Certificates on devices

Public CAs can't issue for a VPN IP address, and HTTP-01 can't reach one. The chart therefore runs a private CA
(10-year, ECDSA). The server certificate rotates automatically (90 days by default). Install the CA once per
device:

```sh
kubectl -n voice get secret voice-ca -o jsonpath='{.data.ca\.crt}' | base64 -d > laura-voice-ca.crt
```

Android: Settings, Security, Encryption & credentials, Install a certificate, CA certificate. Browsers then
trust the gateway. Native apps trust user CAs only if their network security config allows it (the Android
client does for this host).

## 5. Running without Kubernetes

Each component also runs as a plain process, which is useful for development and as a rollback path:
`llama-server --models-preset models.ini --models-max 1 --port 8090`, `uvicorn tts_server:app --port 8091`,
and `uvicorn assistant_server:app --port 8443 --ssl-keyfile ... --ssl-certfile ...` with `LLM_URL`, `TTS_URL`
and `ASSISTANT_KEY` in the environment. Scale the chart's Deployments to zero first: the GPU can't hold both.
