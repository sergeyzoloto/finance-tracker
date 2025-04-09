# finance-tracker

## web app using Spring Boot and React

Finance Tracker is a personal project of a web application for accounting financial transactions. The project was originally intended as a training project and is a platform for the author to experiment, mainly with `React` and `Spring Boot`.

> [!NOTE]
> The project is currently in active development.

## Features/Roadmap

### Authentication

- [x] endpoints created
- [ ] finalize auth

### Transactions

- [x] start
- [ ] finish

### Containerization

- [x] try
- [ ] use

## How to test this

1. Clone this repository
2. [Install Docker Compose](https://docs.docker.com/compose/install/)
3. Create your `.env` in root folder (check `.env.example` file)
4. Run all containers with `docker-compose up`

## Configuring finance-tracker

### Spring Boot environment

- SPRING_DATASOURCE_URL=jdbc:postgresql://:/
- SPRING_DATASOURCE_USERNAME=
- SPRING_DATASOURCE_PASSWORD=
- JSON_WEB_TOKEN_SECRET=
- JSON_WEB_TOKEN_EXPIRATION_TIME=3600000
- JSON_WEB_TOKEN_REFRESH_EXPIRATION_TIME=259200000

### The URL that applications will use as endpoints to connect

- BASE_SERVER_URL=`http://localhost:5000`
- AUTH_SERVER_URL=`http://auth:8081`
- TRANSACTIONS_SERVER_URL=`http://transactions:8082`
