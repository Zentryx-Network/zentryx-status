# ─────────────────────────────────────────────────────────────────
# zentryx-status · multi-stage Docker build
#
# Stage 1: build the fat jar with Eclipse Temurin 21 + Maven (cached
#          .m2 layer keeps incremental builds fast).
# Stage 2: minimal JRE image, runs as a non-root user, exposes 8090.
# ─────────────────────────────────────────────────────────────────

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Cache the dependency resolution layer
COPY pom.xml ./
RUN apk add --no-cache maven && \
    mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package && \
    cp target/zentryx-status-*.jar app.jar

# ── Runtime ──────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

# Non-root user for the runtime — matches the rest of our security model
RUN addgroup -S zentryx && \
    adduser  -S -G zentryx -h /app zentryx && \
    mkdir -p /app/data && \
    chown -R zentryx:zentryx /app

USER zentryx
WORKDIR /app

COPY --from=build --chown=zentryx:zentryx /workspace/app.jar app.jar

EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8090/actuator/health || exit 1

# Enterprise-mode unsealing: the deploy script mints a JWS via
# `sealed-env unseal --totp <code> --deploy-id <sha> --ttl 60` and
# injects it as SEALED_ENV_UNSEAL_TOKEN. Spring Boot reads it on
# startup, validates signature + TTL + deploy_id binding, decrypts
# .env.sealed, exposes vars to the Environment.
#
# If the token is missing, expired, or bound to a different deploy_id,
# the JVM exits non-zero. Docker restart-policy will keep retrying —
# operator must re-deploy with a fresh TOTP to recover. This is by
# design: secrets are never re-decrypted without human approval.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+UseG1GC", "-jar", "app.jar"]
