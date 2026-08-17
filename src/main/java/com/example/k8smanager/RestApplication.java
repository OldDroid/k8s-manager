package com.example.k8smanager;

import java.util.Set;

import io.kubernetes.client.openapi.ApiException;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * JAX-RS entry point. Classes are registered explicitly so no classpath scanning is required.
 */
@ApplicationPath("/api")
public class RestApplication extends Application {

    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(K8sResource.class, K8sApiExceptionMapper.class);
    }

    /** Translates Kubernetes API failures into the same JSON error shape used elsewhere. */
    @Provider
    public static class K8sApiExceptionMapper implements ExceptionMapper<ApiException> {

        @Override
        public Response toResponse(ApiException e) {
            var code = e.getCode();
            var status = (code >= 400 && code <= 599) ? code : 502;
            var detail = (e.getResponseBody() == null || e.getResponseBody().isBlank())
                    ? e.getMessage()
                    : e.getResponseBody();
            return Response.status(status)
                    .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                    .entity(new K8sResource.ApiError(status, "Kubernetes API call failed", detail))
                    .build();
        }
    }
}
