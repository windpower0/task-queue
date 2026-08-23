package com.taskqueue.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskqueue.dto.CreateTaskRequest;
import com.taskqueue.mapper.TaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private TaskMapper taskMapper;

    @BeforeEach
    void cleanUp() {
        taskMapper.delete(new QueryWrapper<>());
    }

    private String body(Object payload, Integer maxAttempts) throws Exception {
        CreateTaskRequest r = new CreateTaskRequest();
        r.setType("t");
        r.setPayload(payload);
        r.setMaxAttempts(maxAttempts);
        return objectMapper.writeValueAsString(r);
    }

    @Test
    void http_createReturns201WithQueuedStatus() throws Exception {
        mockMvc.perform(post("/tasks")
                        .header("Idempotency-Key", "http-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("k", "v"), 3)))
                .andExpect(status().is(201))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void http_claimReturns200WithTaskWhenAvailableAnd204WhenEmpty() throws Exception {
        // no tasks yet -> 204
        mockMvc.perform(post("/workers/w1/tasks/claim"))
                .andExpect(status().isNoContent());

        // create one and claim it -> 200 with the claimed task
        mockMvc.perform(post("/tasks")
                        .header("Idempotency-Key", "http-claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("k", "v"), 3)))
                .andExpect(status().is(201));

        mockMvc.perform(post("/workers/w2/tasks/claim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void http_missingIdempotencyKeyReturns400() throws Exception {
        mockMvc.perform(post("/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("k", "v"), 3)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void http_idempotencyConflictReturns409() throws Exception {
        mockMvc.perform(post("/tasks")
                        .header("Idempotency-Key", "http-conflict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("k", "v"), 3)))
                .andExpect(status().is(201));

        mockMvc.perform(post("/tasks")
                        .header("Idempotency-Key", "http-conflict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("k", "other"), 3)))
                .andExpect(status().isConflict());
    }
}
