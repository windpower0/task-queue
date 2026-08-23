package com.taskqueue.dto;

import jakarta.validation.constraints.NotBlank;

public class CompleteRequest {
    @NotBlank(message = "workerId is required")
    private String workerId;

    @NotBlank(message = "claimToken is required")
    private String claimToken;

    private Object result;

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

    public String getClaimToken() { return claimToken; }
    public void setClaimToken(String claimToken) { this.claimToken = claimToken; }

    public Object getResult() { return result; }
    public void setResult(Object result) { this.result = result; }
}
