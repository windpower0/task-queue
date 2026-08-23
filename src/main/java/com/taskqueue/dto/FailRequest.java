package com.taskqueue.dto;

import jakarta.validation.constraints.NotBlank;

public class FailRequest {
    @NotBlank(message = "workerId is required")
    private String workerId;

    @NotBlank(message = "claimToken is required")
    private String claimToken;

    private Object error;

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

    public String getClaimToken() { return claimToken; }
    public void setClaimToken(String claimToken) { this.claimToken = claimToken; }

    public Object getError() { return error; }
    public void setError(Object error) { this.error = error; }
}
