# Reliable Task Queue & Dispatch Service

一个**单进程**、基于 Spring Boot 3 + MyBatis-Plus 的可靠任务队列与分发服务。实现了文档要求的
任务提交、并发领取（at-most-one winner）、成功/失败状态流转、失败重试、幂等创建与有限租约等核心能力。

---

## 1. 快速开始

### 1.1 自动化测试（零基建）

测试使用 **H2 内存数据库**（`MODE=MySQL`），无需任何外部依赖：

```bash
mvn test
```

全部 16 个用例（含 4 个必需并发/幂等场景）通过，断言确定性强、可重复。

### 1.2 运行服务（需 Docker MySQL 8.0）

```bash
# 1) 启动 MySQL（root/root，库名 taskq）
docker run --name taskq-mysql \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=taskq \
  -p 3306:3306 -d mysql:8.0

# 2) 启动服务（首次启动会自动建表，数据持久化于 MySQL）
mvn spring-boot:run
```

> 表通过 `src/main/resources/schema.sql`（Spring `sql.init.mode=always`）在启动时自动创建；
> 使用 `CREATE TABLE IF NOT EXISTS`，因此**重启不丢数据**。

---

## 2. API 说明

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tasks` | 创建任务（需头 `Idempotency-Key`） |
| POST | `/workers/{workerId}/tasks/claim` | 原子领取一个任务（无任务返回 `204`） |
| POST | `/tasks/{taskId}/complete` | 报告成功（仅持有效租约的 Worker） |
| POST | `/tasks/{taskId}/fail` | 报告失败（未达上限则回到 QUEUED 重试） |
| GET  | `/tasks/{taskId}` | 查询任务 |

### 2.1 创建任务

```bash
curl -X POST http://localhost:8080/tasks \
  -H "Idempotency-Key: req-001" \
  -H "Content-Type: application/json" \
  -d '{"type":"generate-report","payload":{"report_id":"r-123"},"max_attempts":3}'
```

返回（节选）：

```json
{
  "taskId": "3f1c...",
  "type": "generate-report",
  "payload": "{\"report_id\":\"r-123\"}",
  "status": "QUEUED",
  "maxAttempts": 3,
  "attemptCount": 0,
  "createdAt": "...", "updatedAt": "..."
}
```

- `type`、`payload` 必填；`max_attempts` 可选，默认 `3`，上限 `10`（超出自动收敛为上限）。
- 缺 `Idempotency-Key` → **400**；同 Key + 不同内容 → **409**；同 Key + 同内容 → 返回原任务（幂等）。

### 2.2 领取任务

```bash
curl -X POST http://localhost:8080/workers/worker-A/tasks/claim
```

成功返回 `200` + 任务体（含 `claim_token`、`lease_expires_at`）；当前无可领取任务返回 `204`。

### 2.3 报告成功 / 失败

```bash
curl -X POST http://localhost:8080/tasks/{taskId}/complete \
  -H "Content-Type: application/json" \
  -d '{"workerId":"worker-A","claimToken":"<领取时拿到的token>","result":{"url":"s3://x"}}'

curl -X POST http://localhost:8080/tasks/{taskId}/fail \
  -H "Content-Type: application/json" \
  -d '{"workerId":"worker-A","claimToken":"<token>","error":{"code":"TIMEOUT"}}'
```

仅持有**有效（未过期）租约**的 Worker 可操作；错误 Worker / 错误 token / 重复完成 / 已终止任务均返回 **409**（未找到返回 **404**）。

---

## 3. 数据模型

`tasks` 表（DDL 见 `schema.sql`）关键字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `task_id` | VARCHAR(36) PK | UUID |
| `type` | VARCHAR(128) | 任务类型 |
| `payload` | TEXT | 任务输入（JSON 字符串） |
| `status` | VARCHAR(16) | `QUEUED` / `RUNNING` / `SUCCEEDED` / `FAILED` |
| `max_attempts` | INT | 最大尝试次数 |
| `attempt_count` | INT | 已尝试次数（每次 claim +1） |
| `claimed_by` | VARCHAR(128) | 当前持有 Worker |
| `claim_token` | VARCHAR(64) UNIQUE | 每次 claim 轮换的随机令牌 |
| `lease_expires_at` | TIMESTAMP | 租约到期时间（UTC） |
| `last_error` | TEXT | 最近一次失败原因 |
| `result` | TEXT | 成功结果 |
| `completed_at` | TIMESTAMP | 完成时间 |
| `idempotency_key` | VARCHAR(128) UNIQUE | 幂等键 |
| `created_at` / `updated_at` | TIMESTAMP | 时间戳（UTC） |

索引：`UNIQUE(idempotency_key)`、`UNIQUE(claim_token)`、`INDEX(status, created_at)`。

### 状态机

```
                  claim (原子)
   QUEUED -----------------------------> RUNNING
      ^                                   |   |
      |                   complete        |   | fail
      |                      |            |   | (attempt_count < max_attempts)
      |                      v            |   v
      |                  SUCCEEDED     QUEUED (重试)
      |                                   |
      |                  fail              |
      +---------------- FAILED <-----------+ (attempt_count >= max_attempts, 终态)
```

---

## 4. 关键设计

### 4.1 幂等创建

`TaskService.create` 先按 `idempotency_key` 查询：存在且 `type+payload` 一致则返回原任务；
不一致抛 `ConflictException(409)`。插入时若并发触发唯一键冲突（`DuplicateKeyException`），
捕获后重查并比对内容，保证"同 Key 同内容 → 同 ID、同 Key 异内容 → 冲突"的语义。
`payload` 在存储前做**规范化（键排序的 JSON）**，与传入顺序无关。

### 4.2 原子领取（Approach A）

领取是**单条原子 UPDATE**，绝不使用"先查后更新"：

```sql
UPDATE tasks
SET status='RUNNING', claimed_by=?, claim_token=?, lease_expires_at=?,
    attempt_count=attempt_count+1, updated_at=?
WHERE (status='QUEUED' OR (status='RUNNING' AND lease_expires_at < CURRENT_TIMESTAMP))
  AND task_id = (SELECT task_id FROM (
        SELECT task_id FROM tasks
        WHERE (同谓词) ORDER BY created_at ASC LIMIT 1) t)
```

`claimAny(...)` 返回受影响行数，`==1` 即本次领取成功。嵌套派生表（`SELECT ... FROM (SELECT ...) t`）
规避了 MySQL/H2 对"更新中引用同表"的限制。

### 4.3 租约与过期回收

- **有限租约**：领取时写入 `lease_expires_at = now + ttl`（默认 30s，应用侧生成 UTC 时间）。
- **懒回收（folded into claim）**：领取谓词包含 `status='RUNNING' AND lease_expires_at < CURRENT_TIMESTAMP`，
  因此一个过期租约的任务会在**下一次领取时被自动重新领取**，无需独立 reaper 进程。
- **防旧 owner 覆盖**：每次领取都会轮换一个新的随机 `claim_token`，并且 `complete`/`fail` 必须同时满足
  `claimed_by` 匹配 **且** `claim_token` 匹配 **且** `lease_expires_at > CURRENT_TIMESTAMP`。
  旧持有者的令牌在租约过期后失效，即使它之后才来提交也会因校验失败而得到明确 409。

### 4.4 失败与重试

`fail` 同样带租约校验；根据 `attempt_count >= max_attempts` 决定回到 `QUEUED`（可重试）还是置为终态 `FAILED`
（不可再被领取）。`complete` 置 `SUCCEEDED` 并持久化 `completed_at` 与 `result`。

---

## 5. 并发正确性论证

1. **领取的原子性来源数据库行锁**：`claimAny` 是单条 `UPDATE ... WHERE status='QUEUED'`（命中主键/唯一索引），
   数据库会对被选中的行加写锁。并发的两个事务中，后到者需等待先到者提交后才能评估 `WHERE`，
   此时先到者已把该行改为 `RUNNING`，后到者的谓词不再满足 → 受影响行数 `0`。**至多一个事务 `RowsAffected==1`**。
2. **确定性断言**：测试 `ac4_oneTaskTwoWorkersExactlyOneWins` 用 `CyclicBarrier` 让两个 Worker 真正同时发起领取
   （Hikari 多连接，非串行假并发），断言 `assertEquals(1, successCount)`——**绝不是 `>=1`**，
   也不依赖任何随机竞争或 OS 调度结果。
3. **租约校验防止覆盖**：即便旧 owner 在过期后才提交，`claim_token` 已被轮换、`lease_expires_at` 已过期，
   校验必然失败，保证只有当前合法持有者能推进状态。
4. **可重复性**：全部用 H2 内存库 + 固定排序的 SQL，测试结果稳定可重复。

---

## 6. 已知限制与未完成

- **单进程 / 单实例**：未做集群与水平扩展（文档 §8 明确为范围外）。多实例共享同一 MySQL 仍可用，
  但吞吐受单条串行化领取影响（见下）。
- **未使用 SKIP LOCKED**：采用方案 A 的单条 `UPDATE`。在大量任务突发时，每次领取只锁定"队首"一行，
  其余 Worker 可能在同一条热点行上轻微争用（head-of-line 效应），靠 Worker 侧重试恢复吞吐。
  若未来需要更高并发，可迁移到支持 `SKIP LOCKED` 的查询（设计上仅需替换 `claimAny` 的 SQL）。
- **未实现独立 reaper 线程**：过期回收已折叠进领取逻辑（按需回收）。若需要"主动让过期任务尽快重新可见"，
  可补充一个 `@Scheduled` 定时任务周期性触发一次空领取扫描（当前非必需）。
- **时间一致性**：`lease_expires_at` 与 `CURRENT_TIMESTAMP` 的比较要求数据库会话时区为 UTC。
  MySQL 连接串已带 `serverTimezone=UTC`；应用写入的时间戳统一使用 UTC `LocalDateTime`。
  如在非 UTC 环境运行 MySQL，请确保会话时区为 UTC。
- **exactly-once 不保证**：文档 §8 将其列为范围外。本服务保证 at-most-one 领取与持久化重试，
  但"任务副作用的全局恰好一次"需由业务侧幂等处理。
- **认证 / 前端 / 生产部署**：均为范围外，未实现。

---

## 7. AI 使用说明

本项目由 **opencode**（AI 编程助手，模型 `hy3-free` / `ox-alpha-free`）在 TDD 模式下协作实现：

- **需求解析**：从 PDF 抽取需求，逐节（§4–§8）整理为可验收的 AC-1~AC-4。
- **方案设计**：对并发领取给出"数据库原子 UPDATE / Redis 锁 / SELECT…FOR UPDATE"等候选方案并权衡，
  最终选定单条原子 UPDATE（Approach A）；对租约回收、防旧 owner 覆盖、时间戳一致性给出明确决策。
- **实现**：按 `controller / service(+impl) / mapper` 三层 MVC 推进，先写失败测试再实现（AC-1→AC-2→AC-3→AC-4→REFACTOR）。
- **验证**：`mvn test` 全绿（16/16），含确定性并发断言与幂等/状态流转覆盖。

完整的"AI 对话全过程"见 [CONVERSATION.md](https://github.com/windpower0/task-queue/blob/main/CONVERSATION.md)。
