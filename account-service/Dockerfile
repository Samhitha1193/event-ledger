FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
