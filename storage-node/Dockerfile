# Build stage: compiles the whole reactor so heimdall-common is available,
# then packages just this module's jar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY common common
COPY storage-node storage-node
COPY gateway gateway
RUN mvn -q -pl storage-node -am -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN useradd --system --create-home heimdall
COPY --from=build /build/storage-node/target/heimdall-storage-node.jar app.jar
USER heimdall
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
