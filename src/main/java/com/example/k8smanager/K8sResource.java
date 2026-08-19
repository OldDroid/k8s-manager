package com.example.k8smanager;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1OwnerReference;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.KubeConfig;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Single-class controller + service for the namespace-scoped Kubernetes PoC.
 *
 * <p>Everything is namespace-local: the namespace is auto-detected from the mounted service
 * account, so the required RBAC never exceeds a namespaced {@code Role}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class K8sResource {

    // ---------------------------------------------------------------- DTOs

    public record PodInfo(String podName, String podIp, String nodeName, String deploymentName, String status) {}

    public record CallResult(String podName, String url, int status, String contentType, String body, long durationMs) {}

    public record ScaleInfo(String name, int desiredReplicas, int currentReplicas, int readyReplicas,
                            int availableReplicas, int updatedReplicas, boolean ready) {}

    public record ApiError(int status, String message, String detail) {}

    // ------------------------------------------------------------- Plumbing

    private static final java.nio.file.Path SA_NAMESPACE_FILE =
            java.nio.file.Path.of("/var/run/secrets/kubernetes.io/serviceaccount/namespace");

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_TIMEOUT_SECONDS = 60;

    private static final String NAMESPACE = detectNamespace();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(DEFAULT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static volatile ApiClient apiClient;

    /**
     * In-cluster the mounted service account wins. Outside a cluster ({@code mvn liberty:dev}) an
     * explicit override comes next, then the current kubeconfig context — so a local run targets the
     * same namespace as {@code oc project} / {@code kubectl config set-context --current --namespace}.
     */
    private static String detectNamespace() {
        return serviceAccountNamespace()
                .or(() -> nonBlank(System.getenv("KUBERNETES_NAMESPACE")))
                .or(K8sResource::kubeconfigNamespace)
                .orElse("default");
    }

    private static Optional<String> serviceAccountNamespace() {
        try {
            return Files.isReadable(SA_NAMESPACE_FILE)
                    ? nonBlank(Files.readString(SA_NAMESPACE_FILE))
                    : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Current-context namespace from $KUBECONFIG (first usable entry) or ~/.kube/config. */
    private static Optional<String> kubeconfigNamespace() {
        for (var candidate : kubeconfigCandidates()) {
            if (!Files.isReadable(candidate)) {
                continue;
            }
            try (var reader = Files.newBufferedReader(candidate)) {
                var namespace = nonBlank(KubeConfig.loadKubeConfig(reader).getNamespace());
                if (namespace.isPresent()) {
                    return namespace;
                }
            } catch (IOException | RuntimeException e) {
                // unreadable or malformed kubeconfig: fall through to the next candidate
            }
        }
        return Optional.empty();
    }

    private static List<java.nio.file.Path> kubeconfigCandidates() {
        var configured = System.getenv("KUBECONFIG");
        if (configured == null || configured.isBlank()) {
            return List.of(java.nio.file.Path.of(
                    System.getProperty("user.home"), KubeConfig.KUBEDIR, KubeConfig.KUBECONFIG));
        }
        var paths = new ArrayList<java.nio.file.Path>();
        for (var entry : configured.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                paths.add(java.nio.file.Path.of(entry.trim()));
            }
        }
        return paths;
    }

    private static Optional<String> nonBlank(String value) {
        return Optional.ofNullable(value).map(String::trim).filter(v -> !v.isEmpty());
    }

    /** Lazily builds (and caches) the in-cluster client; also works against a local kubeconfig. */
    private static ApiClient client() {
        var cached = apiClient;
        if (cached == null) {
            synchronized (K8sResource.class) {
                cached = apiClient;
                if (cached == null) {
                    try {
                        cached = Config.defaultClient();
                        Configuration.setDefaultApiClient(cached);
                        apiClient = cached;
                    } catch (IOException e) {
                        throw fail(503, "Unable to initialise the Kubernetes client", e.getMessage());
                    }
                }
            }
        }
        return cached;
    }

    private static CoreV1Api core() {
        return new CoreV1Api(client());
    }

    private static AppsV1Api apps() {
        return new AppsV1Api(client());
    }

    private static WebApplicationException fail(int status, String message, String detail) {
        return new WebApplicationException(Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ApiError(status, message, detail))
                .build());
    }

    // ------------------------------------------------------------ Endpoints

    /** Liveness/readiness target that does not touch the Kubernetes API. */
    @GET
    @Path("health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "namespace", NAMESPACE);
    }

    /** {@code GET /api/pods} — every pod in the current namespace. */
    @GET
    @Path("pods")
    public List<PodInfo> pods() throws ApiException {
        var replicaSetOwners = replicaSetOwners();
        return core().listNamespacedPod(NAMESPACE).execute().getItems().stream()
                .map(pod -> toPodInfo(pod, replicaSetOwners))
                .toList();
    }

    /**
     * {@code GET /api/pods/{podName}/call?port=8080&path=/health} — plain HTTP GET straight to the
     * pod IP, bypassing any Service. Returns the target's status code and body.
     */
    @GET
    @Path("pods/{podName}/call")
    public CallResult call(@PathParam("podName") String podName,
                           @QueryParam("port") @DefaultValue("8080") int port,
                           @QueryParam("path") @DefaultValue("/") String path,
                           @QueryParam("timeoutSeconds") @DefaultValue("5") long timeoutSeconds) throws ApiException {

        if (port < 1 || port > 65535) {
            throw fail(400, "Invalid port", "port must be between 1 and 65535, got " + port);
        }
        var timeout = Duration.ofSeconds(Math.clamp(timeoutSeconds, 1, MAX_TIMEOUT_SECONDS));

        var pod = core().readNamespacedPod(podName, NAMESPACE).execute();
        var podIp = Optional.ofNullable(pod.getStatus()).map(s -> s.getPodIP()).orElse(null);
        if (podIp == null || podIp.isBlank()) {
            throw fail(409, "Pod has no IP address yet", "pod=" + podName);
        }

        var target = "http://%s:%d%s".formatted(podIp, port, path.startsWith("/") ? path : "/" + path);
        URI uri;
        try {
            uri = URI.create(target);
        } catch (IllegalArgumentException e) {
            throw fail(400, "Invalid target URL", target);
        }

        var request = HttpRequest.newBuilder(uri).GET().timeout(timeout).build();
        var startedAt = System.nanoTime();
        try {
            var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            var elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            var contentType = response.headers().firstValue("content-type").orElse(null);
            return new CallResult(podName, uri.toString(), response.statusCode(), contentType, response.body(), elapsedMs);
        } catch (IOException e) {
            throw fail(502, "Call to pod failed", uri + " -> " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw fail(504, "Call to pod was interrupted", uri.toString());
        }
    }

    /** {@code GET /api/deployments} — scale state of every deployment in the namespace. */
    @GET
    @Path("deployments")
    public List<ScaleInfo> deployments() throws ApiException {
        return apps().listNamespacedDeployment(NAMESPACE).execute().getItems().stream()
                .map(K8sResource::toScaleInfo)
                .toList();
    }

    /** {@code GET /api/deployments/{name}/scale} — poll this to see when a scale operation settled. */
    @GET
    @Path("deployments/{name}/scale")
    public ScaleInfo scaleStatus(@PathParam("name") String name) throws ApiException {
        return toScaleInfo(apps().readNamespacedDeployment(name, NAMESPACE).execute());
    }

    /** {@code PUT /api/deployments/{name}/scale?replicas=3} — updates the scale subresource. */
    @PUT
    @Path("deployments/{name}/scale")
    public ScaleInfo scale(@PathParam("name") String name, @QueryParam("replicas") int replicas) throws ApiException {
        if (replicas < 0) {
            throw fail(400, "Invalid replica count", "replicas must be >= 0, got " + replicas);
        }

        var scale = apps().readNamespacedDeploymentScale(name, NAMESPACE).execute();
        Optional.ofNullable(scale.getSpec()).orElseThrow(
                () -> fail(500, "Deployment has no scale spec", name)).setReplicas(replicas);
        apps().replaceNamespacedDeploymentScale(name, NAMESPACE, scale).execute();

        // Re-read the deployment so the caller immediately sees desired vs. ready counts.
        return toScaleInfo(apps().readNamespacedDeployment(name, NAMESPACE).execute());
    }

    // -------------------------------------------------------------- Mapping

    private static PodInfo toPodInfo(V1Pod pod, Map<String, String> replicaSetOwners) {
        var meta = pod.getMetadata();
        var owners = meta == null ? null : meta.getOwnerReferences();

        var deploymentName = ownerName(owners, "ReplicaSet")
                .map(rs -> replicaSetOwners.getOrDefault(rs, stripHash(rs)))
                .or(() -> ownerName(owners, "Deployment"))
                .orElse(null);

        var phase = Optional.ofNullable(pod.getStatus()).map(s -> s.getPhase()).orElse("Unknown");
        var terminating = meta != null && meta.getDeletionTimestamp() != null;

        return new PodInfo(
                meta == null ? null : meta.getName(),
                Optional.ofNullable(pod.getStatus()).map(s -> s.getPodIP()).orElse(null),
                Optional.ofNullable(pod.getSpec()).map(s -> s.getNodeName()).orElse(null),
                deploymentName,
                terminating ? "Terminating" : phase);
    }

    private static ScaleInfo toScaleInfo(V1Deployment deployment) {
        var name = Optional.ofNullable(deployment.getMetadata()).map(m -> m.getName()).orElse(null);
        var desired = Optional.ofNullable(deployment.getSpec()).map(s -> s.getReplicas()).orElse(0);
        var status = deployment.getStatus();

        var current = count(status == null ? null : status.getReplicas());
        var ready = count(status == null ? null : status.getReadyReplicas());
        var available = count(status == null ? null : status.getAvailableReplicas());
        var updated = count(status == null ? null : status.getUpdatedReplicas());

        var settled = ready == desired && updated == desired && current == desired;
        return new ScaleInfo(name, desired, current, ready, available, updated, settled);
    }

    /** One list call maps every ReplicaSet in the namespace to its parent Deployment. */
    private static Map<String, String> replicaSetOwners() throws ApiException {
        var owners = new HashMap<String, String>();
        for (var rs : apps().listNamespacedReplicaSet(NAMESPACE).execute().getItems()) {
            var meta = rs.getMetadata();
            if (meta != null && meta.getName() != null) {
                ownerName(meta.getOwnerReferences(), "Deployment").ifPresent(d -> owners.put(meta.getName(), d));
            }
        }
        return owners;
    }

    private static Optional<String> ownerName(List<V1OwnerReference> owners, String kind) {
        return owners == null ? Optional.empty()
                : owners.stream().filter(o -> kind.equals(o.getKind())).map(V1OwnerReference::getName).findFirst();
    }

    /** Fallback when the ReplicaSet's owner is not readable: {@code app-7b89f8c6d} -> {@code app}. */
    private static String stripHash(String replicaSetName) {
        var dash = replicaSetName.lastIndexOf('-');
        return dash > 0 ? replicaSetName.substring(0, dash) : replicaSetName;
    }

    private static int count(Integer value) {
        return value == null ? 0 : value;
    }
}
