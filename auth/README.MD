# Authentication API

## Overview

This API provides authentication and user management services for the system. It includes registration, authentication, token management (including refresh tokens), password management, and user account deletion.

## Authentication Endpoints

### 1. **Register User**

- **Endpoint**: `/register`
- **Method**: `POST`
- **Description**: Registers a new user by providing first name, last name, email, and password.
- **Request Body**:

  ```json
  {
    "firstname": "John",
    "lastname": "Doe",
    "email": "johndoe@example.com",
    "password": "password123"
  }
  ```

- **Response**:

  - **Success**:

    ```json
    {
      "success": true,
      "msg": "User registered successfully",
      "payload": {
        "access_token": "<JWT_ACCESS_TOKEN>",
        "refresh_token": "<JWT_REFRESH_TOKEN>"
      }
    }
    ```

  - **Error**:

    ```json
    {
      "success": false,
      "msg": "Error message"
    }
    ```

### 2. **Authenticate User**

- **Endpoint**: `/authenticate`
- **Method**: `POST`
- **Description**: Authenticates an existing user by verifying the email and password.
- **Request Body**:

  ```json
  {
    "email": "johndoe@example.com",
    "password": "password123"
  }
  ```

- **Response**:

  - **Success**:

    ```json
    {
      "success": true,
      "msg": "User authenticated successfully",
      "payload": {
        "access_token": "<JWT_ACCESS_TOKEN>",
        "refresh_token": "<JWT_REFRESH_TOKEN>"
      }
    }
    ```

  - **Error**:

    ```json
    {
      "success": false,
      "msg": "Invalid credentials"
    }
    ```

### 3. **Refresh Token**

- **Endpoint**: `/refresh-token`
- **Method**: `POST`
- **Description**: Refreshes the JWT access token using a valid refresh token.
- **Request Body**:

  ```json
  {
    "refresh_token": "<REFRESH_TOKEN>"
  }
  ```

- **Response**:

  - **Success**:

    ```json
    {
      "success": true,
      "msg": "Token refreshed successfully",
      "payload": {
        "access_token": "<JWT_ACCESS_TOKEN>",
        "refresh_token": "<JWT_REFRESH_TOKEN>"
      }
    }
    ```

  - **Error**:

    ```json
    {
      "success": false,
      "msg": "Invalid or expired refresh token"
    }
    ```

### 4. **Check Email Existence**

- **Endpoint**: `/email/{email}`
- **Method**: `GET`
- **Description**: Checks if the given email is already registered in the system.
- **Path Parameter**:
  - `email`: The email address to check.
- **Response**:

  - **Success**:

    ```json
    {
      "success": true,
      "msg": "Email existence check successful",
      "payload": true
    }
    ```

  - **Error**:

    ```json
    {
      "success": false,
      "msg": "Error checking email"
    }
    ```

## User Management Endpoints

### 1. **Change Password**

- **Endpoint**: `/users/password`
- **Method**: `PATCH`
- **Description**: Allows an authenticated user to change their password.
- **Request Body**:

  ```json
  {
    "currentPassword": "oldpassword123",
    "newPassword": "newpassword123",
    "confirmationPassword": "newpassword123"
  }
  ```

- **Response**:

  - **Success**:

    ```json
    {
      "success": true,
      "msg": "Password changed successfully"
    }
    ```

  - **Error**:

    ```json
    {
      "success": false,
      "msg": "Error message (e.g., incorrect current password, mismatched passwords)"
    }
    ```

### 2. **Delete User**

- **Endpoint**: `/users/delete`
- **Method**: `DELETE`
- **Description**: Allows an authenticated user to delete their account.
- **Request Body**:

  ```json
  {
    "userId": "<USER_ID>"
  }
  ```

- **Response**:

  - **Success**:

    ```json
    {
      "success": true,
      "msg": "User deleted successfully"
    }
    ```

  - **Error**:

    ```json
    {
      "success": false,
      "msg": "Error message"
    }
    ```

## Logout Endpoint

### 1. **Logout User**

- **Endpoint**: `/logout`
- **Method**: `POST`
- **Description**: Logs out the authenticated user by revoking the current JWT token.
- **Request Headers**:
  - `Authorization`: `Bearer <JWT_ACCESS_TOKEN>`
- **Response**:

  - **Success**:

    ```json
    {
      "success": true,
      "msg": "User logged out successfully"
    }
    ```

  - **Error**:

    ```json
    {
      "success": false,
      "msg": "Error logging out"
    }
    ```

## Token

## Token Management Endpoints

### 1. **Validate Token**

- **Endpoint**: Not exposed directly (internal service).
- **Description**: Validates the provided JWT token to check if it is still valid and not expired.

### 2. **Revoke Token**

- **Endpoint**: Not exposed directly (internal service).
- **Description**: Revokes all the tokens associated with a user when they log out or are removed from the system.

---

## Error Handling

The API returns consistent error messages with the following format in case of any issues:

```json
{
  "success": false,
  "msg": "Detailed error message"
}
```

## JWT Token Structure

All successful authentication responses return both an access token and a refresh token. The tokens are issued in JWT format and include the following claims:

- **sub**: The user's unique identifier (email).
- **exp**: The token expiration time.

Example of a token payload:

```json
{
  "sub": "johndoe@example.com",
  "exp": 1699999999
}
```

## Security Considerations

- **Password Hashing**: Passwords are hashed using a secure algorithm (BCrypt).
- **JWT Signing**: Tokens are signed using a secret key defined in environment variables.
- **Token Expiry**: Access tokens expire in a short time (defined in the configuration), and refresh tokens are used to obtain new access tokens.
