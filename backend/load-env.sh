#!/bin/bash

# Load environment variables from .env file in the parent directory
export $(grep -v '^#' ../.env | xargs)

# Run Maven command
./mvnw spring-boot:run