FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn -B package
FROM eclipse-temurin:21.0.8_9-jre
WORKDIR /app
RUN mkdir /artifacts && chown 10001:10001 /artifacts
COPY --from=build /build/target/report-core-0.1.0.jar /app/report-core.jar
ENV ARTIFACT_ROOT=/artifacts
VOLUME ["/artifacts"]
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/report-core.jar"]
