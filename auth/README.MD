# AuthController API Documentation

## Endpoints

### 1. Authenticate User

#### URL

`/authenticate`

#### Method

`POST`

#### Description

Authenticates a user based on the provided authentication request.

#### Request Body

```json
{
  "username": "string",
  "password": "string"
}
```

- `username`: The username of the user.
- `password`: The password of the user.

#### Response

```json
{
  "token": "string",
  "refreshToken": "string"
}
```

- `token`: The JWT token for the authenticated user.
- `refreshToken`: The refresh token for the authenticated user.

#### Example Request

```bash
curl -X POST "http://yourapi.com/authenticate" -H "Content-Type: application/json" -d '{
  "username": "user1",
  "password": "password123"
}'
```

#### Example Response

```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "dGhpcyBpcyBhIHJlZnJlc2ggdG9rZW4..."
}
```

## UserController API Documentation

### Endpoints

### 1. Change Password

#### URL

`/change-password`

#### Method

`PATCH`

#### Description

Changes the password for the connected user.

#### Request Body

```json
{
  "newPassword": "string"
}
```

- `newPassword`: The new password for the user.

#### Response

- `200 OK`: Indicates the success of the password change.

#### Example Request

```bash
curl -X PATCH "http://yourapi.com/change-password" -H "Content-Type: application/json" -d '{
  "newPassword": "newSecurePassword123"
}'
```

#### Example Response

```json
{
  "status": "success"
}
```

```

```
