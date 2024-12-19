# Stage 1: Build the application using Maven
FROM --platform=linux/amd64 maven:3.8.6-eclipse-temurin-17 AS build

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
FROM --platform=linux/amd64 eclipse-temurin:17-jdk-jammy

# Install Xvfb for virtual display
RUN apt-get update && apt-get install -y \
    libxrender1 \
    libxtst6 \
    libxi6 \
    libxext6 \
    libx11-6 \
    x11-apps \
    xvfb

# Set the working directory
WORKDIR /app

# Copy the built jar from the previous stage
COPY --from=build /app/target/kalshi-project-1.0-SNAPSHOT.jar /app/app.jar

# Set environment variables (to be overridden in docker-compose)
ENV CLUSTER_ADDRESSES=""
ENV BASE_DIR="/app"
ENV NODE_ID=""
ENV CLUSTER_PORT_BASE=""
ENV DISPLAY=:1
VOLUME /tmp/.X11-unix

# Define the command to start Xvfb and the application
CMD ["sh", "-c", "Xvfb :1 -screen 0 1024x768x16 & java -cp /app/app.jar edu.illinois.group8.cluster.ClusterMain"]
# CMD ["firefox"]
