# First stage: Build the application
FROM alpine/java:21-jdk AS builder

WORKDIR /app

# Copy the project files into the build container
COPY . .

# Package the application (this step assumes we're using Maven)
RUN ./mvnw clean package -DskipTests

# Second stage: Create the runtime image
FROM alpine/java:21-jdk

WORKDIR /app

# Copy the JAR file from the builder stage
COPY --from=builder /app/target/*.jar /app/app.jar

EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
