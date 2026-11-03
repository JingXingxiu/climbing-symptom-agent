ARG SENTINEL_VERSION=1.8.9

FROM curlimages/curl:8.12.1 AS downloader
ARG SENTINEL_VERSION
RUN curl --fail --show-error --location --retry 3 \
    --output /tmp/sentinel-dashboard.jar \
    "https://github.com/alibaba/Sentinel/releases/download/${SENTINEL_VERSION}/sentinel-dashboard-${SENTINEL_VERSION}.jar"

FROM eclipse-temurin:17-jre-alpine-3.24
RUN addgroup -S sentinel \
    && adduser -S -G sentinel sentinel \
    && mkdir -p /opt/sentinel \
    && chown -R sentinel:sentinel /opt/sentinel
COPY --from=downloader --chown=sentinel:sentinel /tmp/sentinel-dashboard.jar /opt/sentinel/sentinel-dashboard.jar

USER sentinel
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/sentinel/sentinel-dashboard.jar"]
