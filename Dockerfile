# Use an official OpenJDK runtime as a parent image
FROM openjdk:17-jdk-slim

# Set environment variables
ENV APP_HOME=/app
ENV CONFIG_DIR=/app/config

# Create app directory
WORKDIR $APP_HOME

# Install dependencies and build the project
COPY pom.xml .
COPY src ./src

# Install Maven (if not using a pre-built image with Maven)
RUN apt-get update && \
    apt-get install -y maven && \
    mvn clean package -DskipTests && \
    rm -rf ~/.m2

# Copy the built JAR into the container
COPY target/aeron-cluster-demo-1.0-SNAPSHOT.jar $APP_HOME/app.jar

# Expose necessary ports (modify as needed)
EXPOSE 40123 40124 40125

# Define the entry point
ENTRYPOINT ["java", "-jar", "app.jar"]