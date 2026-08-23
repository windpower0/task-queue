package com.taskqueue.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.taskqueue.dto.CreateResult;
import com.taskqueue.dto.CreateTaskRequest;
import com.taskqueue.entity.Task;
import com.taskqueue.entity.TaskStatus;
import com.taskqueue.exception.BadRequestException;
import com.taskqueue.exception.ConflictException;
import com.taskqueue.exception.TaskNotFoundException;
import com.taskqueue.mapper.TaskMapper;
import com.taskqueue.service.TaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Service
public class TaskServiceImpl implements TaskService {

    private final TaskMapper taskMapper;
    private final ObjectMapper objectMapper;

    @Value("${task.lease.ttl-seconds:30}")
    private int leaseTtlSeconds;

    @Value("${task.max-attempts.default:3}")
    private int defaultMaxAttempts;

    @Value("${task.max-attempts.upper-bound:10}")
    private int maxAttemptsUpperBound;

    public TaskServiceImpl(TaskMapper taskMapper, ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public CreateResult create(CreateTaskRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required");
        }
        if (idempotencyKey.length() > 128) {
            throw new BadRequestException("Idempotency-Key exceeds 128 characters");
        }
        if (request.getType() == null || request.getType().isBlank()) {
            throw new BadRequestException("type is required");
        }
        if (request.getPayload() == null) {
            throw new BadRequestException("payload is required");
        }

        String normalizedPayload = canonical(request.getPayload());

        Task existing = taskMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            if (existing.getType().equals(request.getType())
                    && existing.getPayload().equals(normalizedPayload)) {
                return new CreateResult(existing, false);
            }
            throw new ConflictException("Idempotency-Key reused with different content");
        }

        int maxAttempts = request.getMaxAttempts() != null ? request.getMaxAttempts() : defaultMaxAttempts;
        if (maxAttempts < 1) {
            throw new BadRequestException("max_attempts must be a positive integer");
        }
        if (maxAttempts > maxAttemptsUpperBound) {
            maxAttempts = maxAttemptsUpperBound;
        }

        LocalDateTime now = utcNow();
        Task task = new Task();
        task.setTaskId(UUID.randomUUID().toString());
        task.setType(request.getType());
        task.setPayload(normalizedPayload);
        task.setStatus(TaskStatus.QUEUED);
        task.setMaxAttempts(maxAttempts);
        task.setAttemptCount(0);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setIdempotencyKey(idempotencyKey);

        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            // Race: another request created the same idempotency key concurrently.
            Task again = taskMapper.selectByIdempotencyKey(idempotencyKey);
            if (again != null
                    && again.getType().equals(request.getType())
                    && again.getPayload().equals(normalizedPayload)) {
                return new CreateResult(again, false);
            }
            throw new ConflictException("Idempotency-Key reused with different content");
        }
        return new CreateResult(task, true);
    }

    @Override
    @Transactional
    public Optional<Task> claim(String workerId) {
        String token = UUID.randomUUID().toString();
        LocalDateTime now = utcNow();
        LocalDateTime leaseExpiresAt = now.plusSeconds(leaseTtlSeconds);

        int rows = taskMapper.claimAny(workerId, token, leaseExpiresAt, now);
        if (rows == 1) {
            Task claimed = taskMapper.selectByClaimToken(token);
            if (claimed == null) {
                throw new IllegalStateException(
                        "claimAny returned 1 but selectByClaimToken returned null");
            }
            return Optional.of(claimed);
        }
        return Optional.empty();
    }

    @Override
    @Transactional
    public void complete(String taskId, String workerId, String claimToken, Object result) {
        LocalDateTime now = utcNow();
        String resultStr = result == null ? null : canonical(result);
        int rows = taskMapper.complete(taskId, workerId, claimToken, now, resultStr);
        if (rows != 1) {
            throw diagnostic(taskId, workerId, claimToken);
        }
    }

    @Override
    @Transactional
    public void fail(String taskId, String workerId, String claimToken, Object error) {
        LocalDateTime now = utcNow();
        String errorStr = error == null ? null : canonical(error);
        int rows = taskMapper.fail(taskId, workerId, claimToken, now, errorStr);
        if (rows != 1) {
            throw diagnostic(taskId, workerId, claimToken);
        }
    }

    @Override
    public Task get(String taskId) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new TaskNotFoundException("task not found: " + taskId);
        }
        return task;
    }

    /**
     * Translate a failed complete/fail into a precise, HTTP-mappable error.
     */
    private RuntimeException diagnostic(String taskId, String workerId, String claimToken) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            return new TaskNotFoundException("task not found: " + taskId);
        }
        if (!TaskStatus.RUNNING.equals(task.getStatus())) {
            return new ConflictException("task is not RUNNING (status=" + task.getStatus() + ")");
        }
        if (!workerId.equals(task.getClaimedBy())) {
            return new ConflictException("worker mismatch: " + workerId + " is not the lease holder");
        }
        if (!claimToken.equals(task.getClaimToken())) {
            return new ConflictException("claim token mismatch");
        }
        return new ConflictException("lease expired for the current owner");
    }

    private String canonical(Object value) {
        try {
            JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(value));
            String s = objectMapper.writeValueAsString(node);
            if (s.length() > 65000) {
                throw new BadRequestException("payload exceeds maximum allowed size");
            }
            return s;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("payload must be valid JSON: " + e.getMessage());
        }
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
