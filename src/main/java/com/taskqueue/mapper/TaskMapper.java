package com.taskqueue.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskqueue.entity.Task;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface TaskMapper extends BaseMapper<Task> {

    @Select("SELECT * FROM tasks WHERE idempotency_key = #{key}")
    Task selectByIdempotencyKey(@Param("key") String key);

    @Select("SELECT * FROM tasks WHERE claim_token = #{token}")
    Task selectByClaimToken(@Param("token") String token);

    /**
     * Atomic, single-statement claim (Approach A from the design doc).
     * Picks the oldest eligible task (QUEUED, or RUNNING with an expired lease) and
     * transitions it to RUNNING, assigning a fresh claim_token. Returns the number of
     * rows affected; exactly 1 means this caller won the claim.
     */
    @Update("""
        UPDATE tasks
        SET status = 'RUNNING',
            claimed_by = #{workerId},
            claim_token = #{token},
            lease_expires_at = #{leaseExpiresAt},
            attempt_count = attempt_count + 1,
            updated_at = #{now}
        WHERE (status = 'QUEUED' OR (status = 'RUNNING' AND lease_expires_at < #{now}))
          AND task_id = (
                SELECT task_id FROM (
                    SELECT task_id FROM tasks
                    WHERE (status = 'QUEUED' OR (status = 'RUNNING' AND lease_expires_at < #{now}))
                    ORDER BY created_at ASC
                    LIMIT 1
                ) t
          )
        """)
    int claimAny(@Param("workerId") String workerId,
                 @Param("token") String token,
                 @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
                 @Param("now") LocalDateTime now);

    /**
     * Complete: only the worker holding a non-expired lease may succeed.
     */
    @Update("""
        UPDATE tasks
        SET status = 'SUCCEEDED',
            result = #{result},
            completed_at = #{now},
            updated_at = #{now}
        WHERE task_id = #{taskId}
          AND status = 'RUNNING'
          AND claimed_by = #{workerId}
          AND claim_token = #{token}
          AND lease_expires_at > #{now}
        """)
    int complete(@Param("taskId") String taskId,
                 @Param("workerId") String workerId,
                 @Param("token") String token,
                 @Param("now") LocalDateTime now,
                 @Param("result") String result);

    /**
     * Fail: only the worker holding a non-expired lease may report failure.
     * If the attempt budget is exhausted the task becomes FAILED (terminal),
     * otherwise it returns to QUEUED for re-claim (lease auto-refresh on reclaim).
     */
    @Update("""
        UPDATE tasks
        SET claimed_by = NULL,
            claim_token = NULL,
            lease_expires_at = NULL,
            last_error = #{error},
            status = CASE WHEN attempt_count >= max_attempts THEN 'FAILED' ELSE 'QUEUED' END,
            updated_at = #{now}
        WHERE task_id = #{taskId}
          AND status = 'RUNNING'
          AND claimed_by = #{workerId}
          AND claim_token = #{token}
          AND lease_expires_at > #{now}
        """)
    int fail(@Param("taskId") String taskId,
             @Param("workerId") String workerId,
             @Param("token") String token,
             @Param("now") LocalDateTime now,
             @Param("error") String error);
}
