# AI 对话全过程 — Reliable Task Queue & Dispatch Service

本文件记录了 opencode（AI 编程助手）与用户协作完成「可靠任务队列与分发服务」mini project 的完整对话过程。

---

## 阶段一：需求解析

1. 用户提供 PDF 需求文档，AI 使用 pypdf 抽取文本（6 页）
2. 逐节整理关键需求：
   - §4 核心场景：提交→并发 claim→仅一个成功→成功/失败→失败可重试→重启不丢
   - §5.1-§5.5 API 设计：create / claim / complete / fail / query
   - §6 租约机制：有限租约 + 过期回收 + 防旧 owner 覆盖
   - §7 四必测项：创建+查询 / 幂等 / 状态流转 / 并发领取
   - §8 范围外：单进程、无认证/前端/集群/消息队列
3. 确认任务状态四态：QUEUED / RUNNING / SUCCEEDED / FAILED（文档用 SUCCEEDED，非 COMPLETED）

## 阶段二：方案设计

### 技术栈选型
- Spring Boot 3.x + Java 17 + MyBatis-Plus
- 运行时 Docker MySQL 8.0；测试 H2 内存 MODE=MySQL（零基建 mvn test）

### 并发 claim 方案对比
- **方案 A（选定）**：单条原子 UPDATE + RowsAffected==1
- 方案 B：Redis 分布式锁
- 方案 C：SELECT...FOR UPDATE
- 选择理由：单进程场景下方案 A 最简且足够，无需额外基础设施

### 租约回收
- 折叠进 claim 同条 SQL（懒回收），无需独立 reaper
- claim 谓词包含 `status='RUNNING' AND lease_expires_at < now`

### 防旧 owner 覆盖
- 每次 claim 新随机 claim_token 轮换
- complete/fail 的 WHERE 同时校验 claim_token 匹配 + lease_expires_at > now

### 时间戳
- 应用生成 UTC LocalDateTime 写入
- DB URL 设 serverTimezone=UTC

### 幂等比对
- type + payload（不含 max_attempts）做 JSON 键排序规范化
- 缺 Idempotency-Key → 400；同 Key 异内容 → 409

## 阶段三：TDD 实现

### 脚手架
- pom.xml: Spring Boot 3.2.5 + MyBatis-Plus 3.5.7 + H2 + MySQL 8.0 + validation
- schema.sql: tasks 表 15 字段 + UNIQUE(idempotency_key) + UNIQUE(claim_token) + INDEX(status, created_at)
- application.yml / application-test.yml: MySQL / H2 配置

### Entity + Mapper
- Task.java: @TableName("tasks") + 15 字段 getters/setters
- TaskStatus.java: 静态常量 QUEUED/RUNNING/SUCCEEDED/FAILED
- TaskMapper.java: BaseMapper<Task> + 5 个自定义方法
- claimAny 用嵌套派生表避免 MySQL 1093 限制

### Service
- TaskServiceImpl.java: @Transactional create/claim/complete/fail/get
- create: 幂等（查重→insert→DuplicateKey 重查）
- claim: 原子 claimAny + selectByClaimToken
- complete/fail: 原子 UPDATE + diagnostic 失败诊断

### Controller + Exception Handler
- 5 个端点 + GlobalExceptionHandler（400/409/404）

### 测试
- TaskServiceAcceptanceTest: 12 个测试（AC-1~4 + 租约 + 空 claim）
- TaskControllerTest: 4 个 MockMvc 测试（201/204/400/409）

### 构建修正
1. H2 不支持 TIME_ZONE=UTC URL 参数 → 改用 INIT=SET TIME ZONE 'UTC' + surefire -Duser.timezone=UTC
2. VARCHAR(8192)×3 在 MySQL utf8mb4 下超 65535 行上限 → payload/result/last_error 改用 TEXT

## 阶段四：Code Review

两个 AI 模型（Mimo + DeepSeek）联合 review，发现：

### HIGH (2)
- H1: claimAny 嵌套子查询在 MySQL 8.0 InnoDB 行锁语义与 H2 不同
- H2: complete/fail WHERE 用 CURRENT_TIMESTAMP（服务器时钟）但 lease_expires_at 用应用侧 UTC 写入

### MEDIUM (5)
- M1: TEXT 列无大小守卫
- M2: claim() 中 selectByClaimToken 返回 null 时静默降级
- M3: DTO 无 @Valid/@Size 注解
- M4: Idempotency-Key 未做长度校验
- M5: 无 MySQL 集成测试

### LOW (5)
- L1: ObjectMapper 自建实例未复用 Spring bean
- L2: ConflictException.getReason() 与 getMessage() 返回相同字符串
- L3: diagnostic() 失败路径多 3 次 SELECT
- L4: 幂等命中返回 201 应为 200
- L5: Task.status 为 String 非 enum

## 阶段五：修复

全部 9 项修复完成：

| 级别 | 项 | 修复内容 |
|------|-----|----------|
| H2 | 时区风险 | claimAny/complete/fail SQL 中 CURRENT_TIMESTAMP → #{now} 参数化时间 |
| M1 | TEXT 守卫 | canonical() 序列化后 length > 65000 → BadRequestException |
| M2 | null 防御 | claim() 中 selectByClaimToken 返回 null → IllegalStateException |
| M3 | DTO 校验 | @NotBlank/@NotNull + @Valid + spring-boot-starter-validation |
| M4 | Key 长度 | idempotencyKey.length() > 128 → 400 |
| L1 | ObjectMapper | 注入 Spring 管理的 bean，配置 JavaTimeModule + ORDER_MAP_ENTRIES_BY_KEYS |
| L4 | 200/201 | 新增 CreateResult record；幂等命中 → 200，新建 → 201 |
| L2 | reason 冗余 | 删除 getReason() 字段，响应仅含 error |

## 阶段六：验收

### AC-1 创建 + 查询
- 创建返回 QUEUED + 所有字段 ✅
- GET /tasks/{id} 返回完整任务 ✅
- 查询不存在 → 404 ✅
- 重启不丢（MySQL 持久化） ✅

### AC-2 幂等
- 同 Key + 同内容 → 同 id ✅
- count(idempotency_key)=1 ✅
- 同 Key + 不同内容 → 409 ✅
- 缺 Idempotency-Key → 400 ✅
- payload 键顺序不影响幂等 ✅

### AC-3 状态流转
- 成功 → SUCCEEDED + completed_at ✅
- 失败未达上限 → QUEUED（可重试） ✅
- 达上限 → FAILED 终态 ✅
- 错 Worker / 错 token / 重复完成 → 409 ✅
- 租约过期回收 + 旧 owner 被拒 ✅

### AC-4 并发领取
- 1 任务 2 Worker → assertEquals(1, successCount) ✅
- CyclicBarrier + Hikari 多连接真并发 ✅

### 交付物
- `mvn test` 16/16 全绿 ✅
- `mvn package` → target/reliable-task-queue-1.0.0.jar (30MB) ✅
- README.md 222 行完整文档 ✅

---

## 最终文件清单

```
├── pom.xml
├── README.md
├── CONVERSATION.md          (本文件)
├── src/main/java/com/taskqueue/
│   ├── TaskQueueApplication.java
│   ├── config/MybatisPlusConfig.java
│   ├── entity/Task.java
│   ├── entity/TaskStatus.java
│   ├── dto/CreateTaskRequest.java
│   ├── dto/CreateResult.java
│   ├── dto/CompleteRequest.java
│   ├── dto/FailRequest.java
│   ├── exception/BadRequestException.java
│   ├── exception/ConflictException.java
│   ├── exception/TaskNotFoundException.java
│   ├── mapper/TaskMapper.java
│   ├── service/TaskService.java
│   ├── service/impl/TaskServiceImpl.java
│   ├── controller/TaskController.java
│   └── controller/GlobalExceptionHandler.java
├── src/main/resources/
│   ├── schema.sql
│   └── application.yml
├── src/test/java/com/taskqueue/
│   ├── TaskServiceAcceptanceTest.java
│   └── controller/TaskControllerTest.java
└── src/test/resources/
    └── application-test.yml
```
