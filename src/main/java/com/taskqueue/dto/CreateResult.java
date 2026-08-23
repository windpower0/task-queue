package com.taskqueue.dto;

import com.taskqueue.entity.Task;

public record CreateResult(Task task, boolean created) {
}
