FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY common common
COPY storage-node storage-node
COPY gateway gateway
COPY benchmarks benchmarks
COPY integration-tests integration-tests
RUN mvn -q -pl gateway -am -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN useradd --system --create-home heimdall
COPY --from=build /build/gateway/target/heimdall-gateway-boot.jar app.jar
USER heimdall
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
