package com.taskqueue.controller;

import com.taskqueue.dto.CompleteRequest;
import com.taskqueue.dto.CreateResult;
import com.taskqueue.dto.CreateTaskRequest;
import com.taskqueue.dto.FailRequest;
import com.taskqueue.entity.Task;
import com.taskqueue.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/tasks")
    public ResponseEntity<Task> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateTaskRequest request) {
        CreateResult result = taskService.create(request, idempotencyKey);
        return result.created()
                ? ResponseEntity.status(201).body(result.task())
                : ResponseEntity.ok(result.task());
    }

    @PostMapping("/workers/{workerId}/tasks/claim")
    public ResponseEntity<Task> claim(@PathVariable String workerId) {
        Optional<Task> claimed = taskService.claim(workerId);
        return claimed
                .map(ResponseEntity::ok)
                .orElseGet(ResponseEntity.noContent()::build);
    }

    @PostMapping("/tasks/{taskId}/complete")
    public ResponseEntity<Void> complete(@PathVariable String taskId,
                                         @Valid @RequestBody CompleteRequest request) {
        taskService.complete(taskId, request.getWorkerId(), request.getClaimToken(), request.getResult());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/tasks/{taskId}/fail")
    public ResponseEntity<Void> fail(@PathVariable String taskId,
                                     @Valid @RequestBody FailRequest request) {
        taskService.fail(taskId, request.getWorkerId(), request.getClaimToken(), request.getError());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<Task> get(@PathVariable String taskId) {
        return ResponseEntity.ok(taskService.get(taskId));
    }
}
