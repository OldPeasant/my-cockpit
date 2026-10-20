# Cockpit server image: Angular SPA + Spring Boot server in one container (SPEC.md §11).
# Build from the repository root: `docker build -t cockpit-server .` (Podman: `podman build ...`).
# Runtimes match the repo: Node 24 (.nvmrc), JDK 21 (Gradle toolchain in server/build.gradle.kts).

# --- 1. Web: Angular production build -> /web/dist/web/browser ---
FROM docker.io/library/node:24-bookworm-slim AS web
WORKDIR /web
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    NG_CLI_ANALYTICS=false
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

# --- 2. Server: boot jar with the web build as static resources ---
FROM docker.io/library/eclipse-temurin:21-jdk AS server
WORKDIR /server
# Wrapper + build scripts first, so the Gradle distribution and dependencies stay cached.
COPY server/gradlew server/settings.gradle.kts server/build.gradle.kts ./
COPY server/gradle/ gradle/
RUN ./gradlew --no-daemon --quiet dependencies > /dev/null
COPY server/src/main/ src/main/
COPY --from=web /web/dist/web/browser/ src/main/resources/static/
RUN ./gradlew --no-daemon --quiet bootJar \
    && cp build/libs/*-SNAPSHOT.jar /server/app.jar \
    && java -Djarmode=tools -jar /server/app.jar extract --layers --launcher --destination /server/extracted

# --- 3. Runtime: JRE only, non-root ---
FROM docker.io/library/eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 cockpit \
    && useradd --system --uid 10001 --gid cockpit --home-dir /app --shell /usr/sbin/nologin cockpit \
    && mkdir -p /data \
    && chown cockpit:cockpit /data
WORKDIR /app
# Layered copy: dependencies change less often than the application classes.
COPY --from=server /server/extracted/dependencies/ ./
COPY --from=server /server/extracted/spring-boot-loader/ ./
COPY --from=server /server/extracted/snapshot-dependencies/ ./
COPY --from=server /server/extracted/application/ ./
ENV COCKPIT_DATA_DIR=/data \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
VOLUME /data
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
