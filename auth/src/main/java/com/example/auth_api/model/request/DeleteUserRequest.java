package com.example.auth_api.model.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeleteUserRequest {

    private String userId;

    @JsonCreator
    public DeleteUserRequest(@JsonProperty("userId") String userId) {
        this.userId = userId;
    }
}
