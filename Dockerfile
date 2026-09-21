FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
RUN mvn --batch-mode -DskipTests dependency:go-offline

COPY src ./src
RUN mvn --batch-mode -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --uid 10001 --create-home appuser

COPY --from=build /workspace/target/pr-blast-radius-0.0.1-SNAPSHOT.jar /app/app.jar

USER appuser

ENV PORT=10000
EXPOSE 10000

ENTRYPOINT ["sh","-c","java -Dserver.port=${PORT} -jar /app/app.jar"]
