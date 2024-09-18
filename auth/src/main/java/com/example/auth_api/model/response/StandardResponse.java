package com.example.auth_api.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Standard response class to wrap all API responses.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StandardResponse<T> {

    private boolean success;
    private String msg;
    private T payload;  // This can hold any type of data
}
