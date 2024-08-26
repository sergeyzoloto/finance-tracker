# Dockerfile in backend directory
# First stage: Build the application
FROM alpine/java:21-jdk AS builder

WORKDIR /app

COPY . .

RUN ./mvnw clean package -DskipTests

# Second stage: Create the runtime image
FROM alpine/java:21-jdk

WORKDIR /app

COPY --from=builder /app/target/backend.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
