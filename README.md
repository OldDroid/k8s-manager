# k8s-manager

Namespace-scoped Kubernetes REST PoC — Jakarta EE 10 on Open Liberty 26.0.0.6 (Java 25), driving the
official `io.kubernetes:client-java` 27.0.0.

The namespace is auto-detected from `/var/run/secrets/kubernetes.io/serviceaccount/namespace`, falling
back to `$KUBERNETES_NAMESPACE`, then `default`. Everything the app does stays inside that namespace,
so a namespaced `Role` is enough — no `ClusterRole`.

## Endpoints

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/api/pods` | All pods in the namespace, with the owning deployment resolved via owner references |
| `GET` | `/api/pods/{podName}/call?port=8080&path=/health&timeoutSeconds=5` | Direct HTTP GET to the pod IP, bypassing any Service |
| `GET` | `/api/deployments` | Scale state of every deployment |
| `GET` | `/api/deployments/{name}/scale` | Poll for `ready: true` after scaling |
| `PUT` | `/api/deployments/{name}/scale?replicas=3` | Updates the `scale` subresource, returns fresh state |
| `GET` | `/api/health` | Probe target, does not touch the Kubernetes API |

`GET /api/pods` sample:

```json
[
  {
    "podName": "example-app-7b89f8c6d-abcde",
    "podIp": "10.42.0.15",
    "nodeName": "k3s-node-1",
    "deploymentName": "example-app",
    "status": "Running"
  }
]
```

Pods with a deletion timestamp report `"Terminating"` instead of their phase, so a scale-down is
visible while it happens.

## Build

```bash
mvn clean package
```

Local dev loop against your kubeconfig (`Config.defaultClient()` picks it up automatically):

```bash
mvn liberty:dev
```

Container image:

```bash
docker build -t k8s-manager:1.0.0 .
```

## Local development (`mvn liberty:dev`)

`Config.defaultClient()` resolves credentials in this order: `$KUBECONFIG` → `~/.kube/config` →
in-cluster service account. Locally the kubeconfig wins, so the app authenticates as **your** user
with no extra configuration.

Namespace resolution mirrors that: service account file → `$KUBERNETES_NAMESPACE` →
**kubeconfig current-context namespace** → `default`. So `oc project <ns>` is enough to retarget a
local run, and `GET /api/health` echoes back which namespace was picked.

```bash
oc project mattermost
```

```bash
mvn liberty:dev
```

### Option A — run as yourself (fastest)

`kubeadmin` on CRC is cluster-admin, so nothing is required. Convenient, but it proves nothing about
whether the chart's `Role` is sufficient. To run as yourself with exactly the pod's permissions,
install the Role and bind it to your user:

```bash
helm template k8s-manager ./helm/k8s-manager -n mattermost -s templates/role.yaml | oc apply -f -
```

```bash
oc create rolebinding k8s-manager-dev --role=k8s-manager --user=$(oc whoami) -n mattermost
```

### Option B — run as the ServiceAccount (faithful)

This is the one that actually validates the chart's RBAC before you deploy. Create the SA, Role and
RoleBinding, then mint a short-lived token into a throwaway kubeconfig:

```bash
helm template k8s-manager ./helm/k8s-manager -n mattermost -s templates/serviceaccount.yaml -s templates/role.yaml -s templates/rolebinding.yaml | oc apply -f -
```

```bash
kubectl config --kubeconfig=dev-kubeconfig.yaml set-cluster crc --server=https://api.crc.testing:6443 --insecure-skip-tls-verify=true
```

```bash
kubectl config --kubeconfig=dev-kubeconfig.yaml set-credentials k8s-manager-sa --token=$(oc create token k8s-manager -n mattermost --duration=8h)
```

```bash
kubectl config --kubeconfig=dev-kubeconfig.yaml set-context dev --cluster=crc --user=k8s-manager-sa --namespace=mattermost
```

```bash
kubectl config --kubeconfig=dev-kubeconfig.yaml use-context dev
```

```bash
KUBECONFIG=dev-kubeconfig.yaml mvn liberty:dev
```

A kubeconfig token is bound to `AccessTokenAuthentication` — read once, never refreshed. When the
8h duration lapses you get 401s until you restart. Only the in-cluster path uses the refreshing
`TokenFileAuthentication`. Add `dev-kubeconfig.yaml` to `.gitignore`; it holds a real token.

### Verifying the Role is complete

```bash
oc auth can-i --list --as=system:serviceaccount:mattermost:k8s-manager -n mattermost
```

Each endpoint maps to one check — `get/list pods`, `list replicasets`, `get deployments`,
`update deployments/scale`:

```bash
oc auth can-i update deployments/scale --as=system:serviceaccount:mattermost:k8s-manager -n mattermost
```

## Deploy on k3s

```bash
docker save k8s-manager:1.0.0 | sudo k3s ctr images import -
```

```bash
helm install k8s-manager ./helm/k8s-manager --set ingress.enabled=true --set image.pullPolicy=Never
```

Then `curl http://k8s-manager.localhost/api/pods` (add the host to `/etc/hosts` if needed).

## Deploy on OpenShift

```bash
helm install k8s-manager ./helm/k8s-manager --set openshiftRoute.enabled=true --set image.repository=image-registry.openshift-image-registry.svc:5000/<namespace>/k8s-manager
```

No `runAsUser` is set anywhere, so the `restricted-v2` SCC can assign its own UID; `configure.sh` in
the Dockerfile fixes the group-0 permissions Liberty needs for that.

## Notes

- `restfulWS-3.1` + `jsonb-3.0` resolve to `[cdi-4.0, jndi-1.0, jsonb-3.0, jsonp-2.1, restfulWS-3.1,
  restfulWSClient-3.1]` on the slim kernel — that is the full installed feature set.
- `client-java` 19+ uses the fluent request builders (`listNamespacedPod(ns).execute()`). On an older
  client, drop the `.execute()` calls.
- The Kubernetes client pulls in okhttp/gson/protobuf, so the WAR is ~20 MB despite the slim source.
  Excluding `client-java-proto` trims it further if the footprint matters.
- `SLF4J(W): No SLF4J providers were found` at first API call is expected — the Kubernetes client
  logs via SLF4J and no binding is shipped. Add `org.slf4j:slf4j-simple` if you want its logs.

## Errors

Failures come back in one shape, with the Kubernetes status code passed through when there is one:

```json
{"status":502,"message":"Kubernetes API call failed","detail":"..."}
```
