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
