FROM eclipse-temurin:21.0.12_8-jdk-alpine-3.24 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY config-service/pom.xml config-service/pom.xml
COPY discovery-service/pom.xml discovery-service/pom.xml
COPY gateway-service/pom.xml gateway-service/pom.xml
COPY catalog-service/pom.xml catalog-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY production-service/pom.xml production-service/pom.xml
COPY production-service production-service
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -ntp -pl production-service -am -DskipTests package
FROM eclipse-temurin:21.0.12_8-jre-alpine-3.24
RUN addgroup -S catering && adduser -S catering -G catering
WORKDIR /app
COPY --from=build /workspace/production-service/target/production-service-0.0.1-SNAPSHOT.jar app.jar
USER catering
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
