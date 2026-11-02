# One image for the whole app: the React build is served by Spring Boot on port 8080 (#11)

FROM node:24-alpine AS frontend
WORKDIR /src
# orval generates the API client from the contract during npm run build
COPY contract/ contract/
COPY frontend/package.json frontend/package-lock.json frontend/
RUN cd frontend && npm ci
COPY frontend/ frontend/
RUN cd frontend && npm run build

FROM eclipse-temurin:21-jdk AS backend
WORKDIR /src/backend
COPY backend/gradlew backend/settings.gradle backend/build.gradle ./
COPY backend/gradle/ gradle/
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY backend/src/main/ src/main/
COPY --from=frontend /src/frontend/dist/ src/main/resources/static/
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend /src/backend/build/libs/*.jar app.jar
# Replay reads recorded responses from here (AGENTS.md hard rule 4)
COPY fixtures/ fixtures/
ENV AI_FIXTURES_DIR=/app/fixtures
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
