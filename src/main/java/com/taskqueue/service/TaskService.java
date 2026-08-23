package com.taskqueue.service;

import com.taskqueue.dto.CreateResult;
import com.taskqueue.dto.CreateTaskRequest;
import com.taskqueue.entity.Task;

import java.util.Optional;

public interface TaskService {

    /**
     * Create a task. Idempotent: same Idempotency-Key + same content returns the
     * original task (created=false); same key + different content throws ConflictException.
     */
    CreateResult create(CreateTaskRequest request, String idempotencyKey);

    /**
     * Atomically claim the oldest eligible task for the given worker.
     * Returns the claimed task, or empty if no task is currently claimable (-> 204).
     */
    Optional<Task> claim(String workerId);

    /**
     * Mark a RUNNING task SUCCEEDED. Only the worker holding a valid (non-expired)
     * lease may call this; otherwise a descriptive ConflictException is thrown.
     */
    void complete(String taskId, String workerId, String claimToken, Object result);

    /**
     * Report failure for a RUNNING task. Only the lease holder may call this.
     * Returns the task to QUEUED for retry, or to FAILED if the attempt budget is
     * exhausted.
     */
    void fail(String taskId, String workerId, String claimToken, Object error);

    /**
     * Fetch a task by id. Throws TaskNotFoundException if absent.
     */
    Task get(String taskId);
}
