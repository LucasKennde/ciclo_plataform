FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY backend ./backend
ARG SERVICE
RUN mvn -B -pl backend/${SERVICE} -am package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
ARG SERVICE
COPY --from=build /workspace/backend/${SERVICE}/target/${SERVICE}-1.0.0-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
