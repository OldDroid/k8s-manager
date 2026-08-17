# ---------- build ----------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# Best-effort dependency pre-fetch so source-only changes reuse this layer.
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline || true

COPY src ./src
RUN mvn -B -DskipTests clean package

# ---------- runtime ----------
FROM icr.io/appcafe/open-liberty:26.0.0.6-kernel-slim-java25-openj9-ubi-minimal

COPY --chown=1001:0 src/main/liberty/config/server.xml /config/server.xml

# Install only the features referenced by server.xml into the slim kernel.
RUN features.sh

COPY --chown=1001:0 --from=build /build/target/k8s-manager.war /config/apps/

# Shared class cache + AOT warm-up; also fixes group-0 permissions for OpenShift's random UID.
RUN configure.sh

EXPOSE 9080
