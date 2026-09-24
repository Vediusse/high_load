FROM eclipse-temurin:21.0.12_8-jdk-alpine-3.24 AS build

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw mvnw
COPY pom.xml pom.xml
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

COPY src src
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:21.0.12_8-jre-alpine-3.24

RUN addgroup -S catering && adduser -S catering -G catering
WORKDIR /app

COPY --from=build /workspace/target/corporate-catering-0.0.1-SNAPSHOT.jar app.jar

USER catering
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
