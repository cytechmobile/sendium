FROM container-registry.oracle.com/graalvm/jdk:25
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport"
WORKDIR /app
COPY target/quarkus-app/ .
# Expose the port for the Quarkus application
EXPOSE 8080 2775
VOLUME ["/app/conf", "/app/logs"]
CMD ["java", "-jar", "/app/quarkus-run.jar"]