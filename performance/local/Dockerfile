FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY sendium-core sendium-core
COPY sendium-app sendium-app
COPY performance/harness performance/harness
RUN chmod +x mvnw && ./mvnw -B -ntp -DskipTests -pl sendium-app -am package \
    && ./mvnw -B -ntp -DskipTests -Pperformance -pl performance/harness package

FROM amazonlinux:2023 AS node
RUN dnf install -y awscli-2 python3 openssl tar gzip procps-ng && dnf clean all \
    && mv /usr/bin/aws /usr/bin/aws-real
COPY performance/local/agent.py /opt/local/agent.py
COPY performance/local/tls.py /opt/local/tls.py
RUN printf '#!/bin/sh\nexec python3 /opt/local/agent.py aws "$@"\n' > /usr/bin/aws \
    && printf '#!/bin/sh\nexec python3 /opt/local/agent.py unit "$@"\n' > /usr/bin/systemd-run \
    && chmod 755 /usr/bin/aws /usr/bin/systemd-run
ENV PYTHONDONTWRITEBYTECODE=1
CMD ["sleep", "infinity"]

FROM python:3.13-slim AS control
RUN apt-get update && apt-get install -y --no-install-recommends docker-cli curl unzip openssl \
    && case "$(uname -m)" in x86_64) ARCH=x86_64;; aarch64) ARCH=aarch64;; *) exit 1;; esac \
    && curl --fail --location --silent --show-error "https://awscli.amazonaws.com/awscli-exe-linux-${ARCH}.zip" -o /tmp/aws.zip \
    && unzip -q /tmp/aws.zip -d /tmp && /tmp/aws/install && rm -rf /tmp/aws /tmp/aws.zip /var/lib/apt/lists/*
WORKDIR /workspace
COPY performance/contracts performance/contracts
COPY performance/presets performance/presets
COPY performance/deployment performance/deployment
COPY performance/infrastructure performance/infrastructure
COPY performance/resources performance/resources
COPY performance/workflow performance/workflow
COPY performance/local performance/local
COPY --from=build /src/sendium-app/target/quarkus-app /opt/build/quarkus-app
COPY --from=build /src/performance/harness/target/sendium-performance-harness-*.jar /opt/build/harness/
COPY source-context.json /opt/build/source-context.json
RUN python -m pip install --no-cache-dir -r performance/workflow/requirements-dev.txt
ENV PYTHONDONTWRITEBYTECODE=1
ENTRYPOINT ["python", "/workspace/performance/local/lab.py"]
