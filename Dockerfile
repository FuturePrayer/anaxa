# syntax=docker/dockerfile:1.7

FROM maven:3.9.15-eclipse-temurin-26 AS build
WORKDIR /workspace

COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode -pl anaxa-server -am clean package -DskipTests

FROM eclipse-temurin:26-jre
WORKDIR /opt/anaxa

RUN groupadd --system anaxa && useradd --system --gid anaxa --home-dir /opt/anaxa anaxa

COPY --from=build /workspace/anaxa-server/target/anaxa-server-*.jar /opt/anaxa/anaxa-server.jar

RUN mkdir -p /data/anaxa && chown -R anaxa:anaxa /opt/anaxa /data/anaxa

USER anaxa
EXPOSE 30720
VOLUME ["/data/anaxa"]

ENTRYPOINT ["java", "--enable-preview", "-jar", "/opt/anaxa/anaxa-server.jar"]
CMD ["--host=0.0.0.0", "--port=30720", "--data-dir=/data/anaxa"]
