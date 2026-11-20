# Build once; the same image runs as gateway, worker or mock-lab, chosen by SPRING_PROFILES_ACTIVE.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/whatsapp-agent-gateway-0.1.0.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
