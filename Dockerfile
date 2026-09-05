# Build and run the ApnaTutor backend.
#
# A Dockerfile rather than Railway's automatic Nixpacks detection, because
# pom.xml targets Java 26 and the Nixpacks Java provider does not offer it. This
# pins the toolchain explicitly instead of hoping the platform guesses right.
#
# Two stages so the JDK, the Maven repository and the source tree stay out of the
# published image: only a JRE and one jar ship, which is a much smaller image and
# a much smaller attack surface.

# ---- build -----------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /build

# Maven from the image, deliberately, rather than ./mvnw. The wrapper exists to
# download a pinned Maven version, and this image already ships one, so running
# the wrapper would spend a network round trip fetching what is already present.
# (Line endings are not the reason: .gitattributes pins /mvnw to eol=lf, so it
# checks out fine on Linux.)
COPY pom.xml ./

# Best effort dependency warm-up: it makes an unchanged pom.xml a cached layer so
# later pushes rebuild in seconds. Failure here is not fatal — the package step
# below resolves anything missing — so a plugin that dislikes go-offline cannot
# break the deployment.
RUN mvn -B -DskipTests dependency:go-offline || true

COPY src ./src

# Tests are not run here. They need a live PostgreSQL, which a build container
# does not have; CI runs them against a real database on every push instead.
RUN mvn -B -DskipTests clean package

# ---- run -------------------------------------------------------------------
FROM eclipse-temurin:26-jre-noble
WORKDIR /app

# Unprivileged: nothing this process does needs root, and a container escape is
# worth less without it.
RUN useradd --create-home --shell /usr/sbin/nologin apnatutor

COPY --from=build /build/target/*.jar app.jar

# Only the executable jar matches *.jar — Spring Boot's repackage step leaves the
# pre-packaged one as .jar.original, so this cannot copy the wrong artifact.

# Present so local storage has somewhere to write. It is deliberately NOT a
# volume: this filesystem is replaced on every redeploy, which is exactly why
# APNATUTOR_STORAGE_PROVIDER must become s3 before real uploads matter.
RUN mkdir -p /app/uploads && chown -R apnatutor:apnatutor /app

USER apnatutor
EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the JVM reads the container limit,
# so the heap follows the Railway plan instead of being wrong on both a small
# instance (killed) and a large one (idle memory).
#
# --server.port is passed as an argument because a command line argument outranks
# every other property source, so it wins over application.yml's SERVER_PORT
# default no matter what else is set. Railway supplies PORT; 8080 keeps the image
# runnable anywhere that does not.
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=75 -jar app.jar --server.port=${PORT:-8080}"]
