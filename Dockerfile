# syntax=docker/dockerfile:1

# ---- build ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test

# ---- runtime ----
FROM eclipse-temurin:25-jre
ENV TZ=America/Sao_Paulo
RUN groupadd --system azimute && useradd --system --gid azimute azimute
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
USER azimute
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=America/Sao_Paulo", "-jar", "/app/app.jar"]
