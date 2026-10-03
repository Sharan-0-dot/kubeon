# ---- Build Stage ----
FROM eclipse-temurin:21 AS build

WORKDIR /workspace

# Copy Maven wrapper and POM first for dependency caching
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy source and build
COPY src/ src/
RUN ./mvnw package -DskipTests -B

# ---- Runtime Stage ----
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="Sharan <sharansc482@gmail.com>"
LABEL description="Kubeon — AI-powered Kubernetes incident detection and diagnosis"
LABEL version="0.0.1"

# Create non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup -u 1000

WORKDIR /app

# Copy JAR from build stage
COPY --from=build /workspace/target/*.jar app.jar

# Set ownership
RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
