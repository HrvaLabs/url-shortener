FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jre AS layers

WORKDIR /build

COPY target/*.jar app.jar

RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre

RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app

COPY --from=layers --chown=app:app /build/extracted/dependencies/ ./
COPY --from=layers --chown=app:app /build/extracted/spring-boot-loader/ ./
COPY --from=layers --chown=app:app /build/extracted/snapshot-dependencies/ ./
COPY --from=layers --chown=app:app /build/extracted/application/ ./
COPY --chown=app:app docker/healthcheck.sh /app/healthcheck.sh

USER app

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 CMD ["/app/healthcheck.sh"]

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Duser.timezone=UTC", "-jar", "app.jar"]
