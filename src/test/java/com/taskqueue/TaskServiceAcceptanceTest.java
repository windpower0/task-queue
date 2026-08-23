package com.taskqueue;

import com.taskqueue.dto.CreateResult;
import com.taskqueue.dto.CreateTaskRequest;
import com.taskqueue.entity.Task;
import com.taskqueue.entity.TaskStatus;
import com.taskqueue.exception.BadRequestException;
import com.taskqueue.exception.ConflictException;
import com.taskqueue.exception.TaskNotFoundException;
import com.taskqueue.mapper.TaskMapper;
import com.taskqueue.service.TaskService;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class TaskServiceAcceptanceTest {

    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskMapper taskMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void cleanUp() {
        taskMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
    }

    private CreateTaskRequest req(Object payload, Integer maxAttempts) {
        CreateTaskRequest r = new CreateTaskRequest();
        r.setType("generate-report");
        r.setPayload(payload);
        r.setMaxAttempts(maxAttempts);
        return r;
    }

    private boolean jsonEquals(String storedJson, Object original) throws Exception {
        JsonNode a = objectMapper.readTree(storedJson);
        JsonNode b = objectMapper.valueToTree(original);
        return a.equals(b);
    }

    // ---------- AC-1: create + query round trip ----------
    @Test
    void ac1_createReturnsQueuedAndQueryClosesLoop() {
        Task created = taskService.create(req(Map.of("report_id", "r1"), 3), "ac1-key").task();
        assertEquals(TaskStatus.QUEUED, created.getStatus());
        assertEquals(0, created.getAttemptCount());
        assertNotNull(created.getTaskId());
        assertEquals(3, created.getMaxAttempts());

        Task fetched = taskService.get(created.getTaskId());
        assertEquals("generate-report", fetched.getType());
        assertDoesNotThrow(() -> assertTrue(jsonEquals(fetched.getPayload(), Map.of("report_id", "r1"))));
        assertEquals(TaskStatus.QUEUED, fetched.getStatus());
    }

    @Test
    void ac1_queryMissingTaskThrowsNotFound() {
        assertThrows(TaskNotFoundException.class, () -> taskService.get("does-not-exist"));
    }

    // ---------- AC-2: idempotency ----------
    @Test
    void ac2_sameKeySameContentReturnsSameId() {
        Task t1 = taskService.create(req(Map.of("a", 1), 3), "ac2-ka").task();
        Task t2 = taskService.create(req(Map.of("a", 1), 3), "ac2-ka").task();
        assertEquals(t1.getTaskId(), t2.getTaskId());

        long count = taskMapper.selectList(null).stream()
                .filter(t -> "ac2-ka".equals(t.getIdempotencyKey()))
                .count();
        assertEquals(1, count);
    }

    @Test
    void ac2_sameKeyDifferentContentConflicts() {
        taskService.create(req(Map.of("a", 1), 3), "ac2-kb");
        ConflictException ex = assertThrows(ConflictException.class,
                () -> taskService.create(req(Map.of("a", 2), 3), "ac2-kb"));
        assertTrue(ex.getMessage().contains("different content"));
    }

    @Test
    void ac2_missingIdempotencyKeyIsBadRequest() {
        assertThrows(BadRequestException.class,
                () -> taskService.create(req(Map.of("a", 1), 3), null));
    }

    @Test
    void ac2_keyNormalizesPayloadRegardlessOfKeyOrder() {
        Task t1 = taskService.create(req(Map.of("x", 1, "y", 2), 3), "ac2-kc").task();
        Task t2 = taskService.create(req(Map.of("y", 2, "x", 1), 3), "ac2-kc").task();
        assertEquals(t1.getTaskId(), t2.getTaskId());
    }

    // ---------- AC-3: state transitions ----------
    @Test
    void ac3_successTransitionToSucceeded() {
        Task created = taskService.create(req(Map.of("id", 1), 3), "ac3-ok").task();
        Task claimed = taskService.claim("w1").orElseThrow();
        assertEquals(TaskStatus.RUNNING, claimed.getStatus());
        assertEquals("w1", claimed.getClaimedBy());
        assertNotNull(claimed.getClaimToken());

        taskService.complete(claimed.getTaskId(), "w1", claimed.getClaimToken(), Map.of("url", "s3://x"));

        Task done = taskService.get(claimed.getTaskId());
        assertEquals(TaskStatus.SUCCEEDED, done.getStatus());
        assertNotNull(done.getCompletedAt());
        assertEquals("w1", done.getClaimedBy());
    }

    @Test
    void ac3_failureRetriesThenFailsTerminal() {
        Task created = taskService.create(req(Map.of("id", 2), 3), "ac3-fail").task();
        for (int i = 0; i < 3; i++) {
            Task claimed = taskService.claim("w" + i).orElseThrow();
            taskService.fail(claimed.getTaskId(), "w" + i, claimed.getClaimToken(), Map.of("code", "E"));
            Task after = taskService.get(claimed.getTaskId());
            if (i < 2) {
                assertEquals(TaskStatus.QUEUED, after.getStatus(), "attempt " + (i + 1) + " should requeue");
            } else {
                assertEquals(TaskStatus.FAILED, after.getStatus(), "attempt 3 should be terminal FAILED");
            }
        }
        // terminal FAILED task must not be claimable
        assertTrue(taskService.claim("wX").isEmpty());
    }

    @Test
    void ac3_wrongWorkerOrTokenOrDuplicateIsRejected() {
        Task created = taskService.create(req(Map.of("id", 3), 3), "ac3-boundary").task();
        Task claimed = taskService.claim("w1").orElseThrow();

        // wrong worker
        assertThrows(ConflictException.class,
                () -> taskService.complete(claimed.getTaskId(), "w2", claimed.getClaimToken(), null));
        // wrong token
        assertThrows(ConflictException.class,
                () -> taskService.complete(claimed.getTaskId(), "w1", "wrong-token", null));
        // correct -> success
        taskService.complete(claimed.getTaskId(), "w1", claimed.getClaimToken(), null);
        // duplicate complete on terminal task
        assertThrows(ConflictException.class,
                () -> taskService.complete(claimed.getTaskId(), "w1", claimed.getClaimToken(), null));
    }

    // ---------- §6: lease expiry reclaim + stale owner prevention ----------
    @Test
    void leaseExpiry_allowsReclaimAndInvalidatesOldOwner() {
        Task created = taskService.create(req(Map.of("id", 4), 3), "lease-key").task();
        Task first = taskService.claim("w1").orElseThrow();

        // force the lease into the past without completing
        taskMapper.update(null, new UpdateWrapper<Task>()
                .set("lease_expires_at", LocalDateTime.now().minusSeconds(60))
                .eq("task_id", first.getTaskId()));

        // a second worker should be able to reclaim the expired lease
        Task reclaimed = taskService.claim("w2").orElseThrow();
        assertEquals("w2", reclaimed.getClaimedBy());

        // the original owner's token must no longer be valid
        assertThrows(ConflictException.class,
                () -> taskService.complete(first.getTaskId(), "w1", first.getClaimToken(), null));
    }

    @Test
    void claimReturnsEmptyWhenNoTaskIsAvailable() {
        assertTrue(taskService.claim("w-idle").isEmpty());
    }

    // ---------- AC-4: concurrent claim, exactly one wins ----------
    @Test
    void ac4_oneTaskTwoWorkersExactlyOneWins() throws Exception {
        taskService.create(req(Map.of("id", 5), 3), "ac4-key");

        int workers = 2;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CyclicBarrier gate = new CyclicBarrier(workers);
        List<Future<Optional<Task>>> futures = new ArrayList<>();

        for (int i = 0; i < workers; i++) {
            String workerId = "ac4-worker-" + i;
            futures.add(pool.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return taskService.claim(workerId);
            }));
        }

        AtomicInteger successCount = new AtomicInteger(0);
        for (Future<Optional<Task>> f : futures) {
            if (f.get(10, TimeUnit.SECONDS).isPresent()) {
                successCount.incrementAndGet();
            }
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // THE deterministic assertion: not ">= 1", exactly 1
        assertEquals(1, successCount.get());

        List<Task> all = taskMapper.selectList(null);
        assertEquals(1, all.size());
        Task t = all.get(0);
        assertEquals(TaskStatus.RUNNING, t.getStatus());
        assertEquals(1, t.getAttemptCount());
        assertNotNull(t.getClaimToken());
    }
}
