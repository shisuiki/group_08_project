# Stage 1: Build the application using Maven
FROM maven:3.8.6-eclipse-temurin-17 AS build

# Set the working directory
WORKDIR /app

# Copy the pom.xml and download dependencies
COPY pom.xml .
RUN mvn dependency:go-offline

# Copy the source code
COPY src ./src

# Build the application
RUN mvn package

# Stage 2: Create a lightweight image for running the app
FROM eclipse-temurin:17-jdk-jammy

# Set the working directory
WORKDIR /app

# Copy the built jar from the previous stage
COPY --from=build /app/target/kalshi-project-1.0-SNAPSHOT.jar /app/app.jar

# Set environment variables (to be overridden in docker-compose)
ENV CLUSTER_ADDRESSES=""
ENV BASE_DIR="/app"
ENV NODE_ID=""
ENV CLUSTER_PORT_BASE=""
ENV BACKEND_PROFILE="docker"
ENV BACKEND_JOURNAL_ROOT="/app/journal"
ENV AERON_CHANNEL="aeron:udp?endpoint=0.0.0.0:40456"

# Define the command to run the application
CMD ["java", "-cp", "/app/app.jar", "edu.illinois.group8.cluster.ClusterMain"]
