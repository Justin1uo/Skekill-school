# 高校选课系统 · 开发蓝图（AI 协作版）

> 版本：v1.0 ｜ 2026-09-25
> 用途：这份文件是给 VSCode 中的 AI 编码 Agent（Codex / Claude Code / Cursor / Trae 等）看的项目规格说明书。
> 把本文件放到项目根目录，让 Agent 先读它再开工。

---

## 0. 给接手 AI Agent 的开场白（可直接复制）

```
请先阅读本目录下的 course-selection-blueprint.md，这是本项目的完整规格说明。

背景：我是一名大三本科生，这是用来求职的第二个项目（简历项目），核心目标是
体现高并发处理能力。总工期 30 小时，我会和你结对编程。

三条硬性要求：
1. 严格按 Stage 0 → Stage 6 分阶段推进。一次只做一个 Stage，完成后停下来等我验证。
2. 每个 Stage 结束必须给出可执行的验证命令（curl / 单元测试 / 日志断言），
   我跑通了才进入下一 Stage。不要一次性生成整个项目。
3. 凡是涉及并发的代码（Lua 脚本、锁、事务边界、MQ 幂等、限流），
   你必须在代码里写注释解释"为什么这里必须这样做"，
   并在回复里单独说明并发语义。这部分我要逐行看懂，因为面试会被追问。

另外：不要为了实现方便而简化并发正确性。遇到设计取舍（比如加锁还是不加锁）
先停下来问我，不要自作主张。压测数据必须实测，不能估算。
```

---

## 1. 项目定位

| 项 | 内容 |
|---|---|
| 项目形态 | 单体 Spring Boot 3 应用（**不做微服务**，P1 阶段再拆） |
| 核心卖点 | 高并发下的**并发正确性**：超卖、重复选课、时间冲突、学分超限，四个问题一个都不能出现 |
| 场景价值 | 选课开放瞬间的抢课洪峰是真实场景；GitHub 上现有选课系统全是毕设 CRUD（SSM/Servlet，2020-2023），没有高并发版本 |
| 简历目标 | 4 条 bullet，占简历 10 行左右 |
| 工期 | 30 小时 |

### 为什么不用秒杀场景

秒杀只有"库存"一个约束。选课多了三个：

1. **时间冲突**：同一时段不能选两门课 → 需要"读已选时段 → 判冲突 → 写入"原子化
2. **学分上限**
3. **一人一课**（同一门课不能重复选）

这四个约束在并发下同时成立，才是这个项目的技术含量所在。

---

## 2. 技术栈（版本固定，不要擅自升级）

| 层 | 选型 | 备注 |
|---|---|---|
| JDK | 17 | |
| 框架 | Spring Boot 3.2.x | |
| ORM | MyBatis-Plus 3.5.x | |
| DB | MySQL 8.0 | |
| 缓存 | Redis 7.x + Caffeine（本地 L1） | Lua 脚本、原子扣减 |
| 分布式锁 | Redisson 3.27+ | 看门狗续期、可重入锁 |
| 消息 | RabbitMQ 3.x | 异步落库、死信 |
| 限流 | Redis + Lua 令牌桶（自研） | 不用 Sentinel，省部署成本 |
| 压测 | JMeter 5.6 | |
| 部署 | Docker Compose + Nginx | 服务器 2G 内存 |

P1 预留（**本期不做**）：Spring Cloud Alibaba（Nacos + Gateway + OpenFeign）。

---

## 3. 目录结构

```
course-selection/
├── sql/
│   └── schema.sql                    # 建表 + 索引 + 初始数据
├── src/main/java/com/luohaoyu/course/
│   ├── CourseApplication.java
│   ├── common/
│   │   ├── Result.java               # 统一响应
│   │   ├── ErrorCode.java            # 错误码枚举
│   │   ├── BizException.java
│   │   └── GlobalExceptionHandler.java
│   ├── config/
│   │   ├── RedisConfig.java          # 序列化、Lua 脚本 Bean 加载
│   │   ├── RedissonConfig.java
│   │   ├── CacheConfig.java          # Caffeine L1 + Redis L2
│   │   └── AsyncConfig.java
│   ├── controller/
│   │   ├── CourseController.java
│   │   ├── SelectionController.java
│   │   └── AdminController.java       # 预热、压测数据生成
│   ├── service/
│   │   ├── CourseService.java
│   │   ├── SelectionService.java      # 编排层：限流 + 锁 + Lua，无 @Transactional
│   │   ├── SelectionTxService.java    # 事务层：单独 Bean，承载 @Transactional
│   │   └── impl/
│   ├── mapper/
│   │   ├── CourseMapper.java
│   │   ├── CourseScheduleMapper.java
│   │   └── SelectionMapper.java
│   ├── domain/
│   │   ├── entity/  dto/  vo/
│   ├── concurrent/
│   │   ├── SelectionLua.java          # Lua 脚本封装
│   │   └── TokenBucketLimiter.java
│   ├── mq/
│   │   ├── SelectionProducer.java
│   │   └── SelectionConsumer.java
│   └── warmup/
│       └── WarmupRunner.java
├── src/main/resources/
│   ├── application.yml
│   ├── lua/
│   │   ├── deduct_course.lua          # 方案 B：课程维度判重 + 扣减
│   │   ├── deduct_full.lua            # 方案 A：全原子（含时段/学分），Stage 5 对比用
│   │   └── token_bucket.lua
│   └── mapper/*.xml
├── jmeter/
│   └── course-select.jmx
├── docker/
│   ├── docker-compose.yml
│   └── Dockerfile
└── README.md
```

---

## 4. 数据库设计

```sql
CREATE TABLE student (
  id           BIGINT PRIMARY KEY,
  student_no   VARCHAR(32) NOT NULL UNIQUE,
  name         VARCHAR(64) NOT NULL,
  grade        VARCHAR(16),
  major        VARCHAR(64),
  max_credit   INT NOT NULL DEFAULT 30,
  created_at   DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE course (
  id             BIGINT PRIMARY KEY,
  course_code    VARCHAR(32) NOT NULL UNIQUE,
  name           VARCHAR(128) NOT NULL,
  teacher        VARCHAR(64),
  credit         INT NOT NULL,
  capacity       INT NOT NULL,
  selected_count INT NOT NULL DEFAULT 0,   -- 由 MQ 消费端累加，Redis 为准
  status         TINYINT NOT NULL DEFAULT 1, -- 1 开放 0 关闭
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 一门课可占多个时段；时段用「周几 + 起始节次 + 结束节次」表达
CREATE TABLE course_schedule (
  id           BIGINT PRIMARY KEY,
  course_id    BIGINT NOT NULL,
  day_of_week  TINYINT NOT NULL,  -- 1-7
  start_period TINYINT NOT NULL,  -- 1-12
  end_period   TINYINT NOT NULL,
  INDEX idx_course (course_id)
);

CREATE TABLE selection (
  id          BIGINT PRIMARY KEY,
  student_id  BIGINT NOT NULL,
  course_id   BIGINT NOT NULL,
  status      TINYINT NOT NULL DEFAULT 1,  -- 1 已选  0 已退
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_student_course (student_id, course_id),  -- ★ 并发正确性的最后防线
  INDEX idx_student (student_id),
  INDEX idx_course (course_id)
);
```

**必须向 Agent 强调的索引设计理由（面试会问）：**

- `uk_student_course`：Redis 锁在主从切换时可能失效，唯一索引是**兜底防重**，不是可选项
- `idx_student`：冲突检测要按学生查已选课程
- 不建 `course_id` 单列索引以外的冗余索引

**测试数据量**：5000 学生 × 50 门课 × 每门 100 容量。Stage 1 用脚本生成。

---

## 5. 接口清单

| 方法 | 路径 | 说明 | 阶段 |
|---|---|---|---|
| GET | `/api/courses` | 课程列表（走 L1+L2 缓存） | S2 |
| GET | `/api/courses/{id}` | 课程详情 | S2 |
| POST | `/api/selection` | **选课（核心压测接口）** body: `{studentId, courseId}` | S3 |
| DELETE | `/api/selection/{courseId}` | 退选（释放名额与时段） | S3（可砍） |
| POST | `/api/selection/change` | 换课：退 A 选 B，**Redisson 锁的真实落点** | S3（可砍） |
| GET | `/api/selection/mine` | 我的课表 | S1 |
| POST | `/api/admin/warmup` | 预热课程与名额到 Redis（分布式锁保证单实例执行） | S2 |
| POST | `/api/admin/gen-data` | 生成压测数据 | S1 |
| GET | `/actuator/health` | 健康检查 | S1 |

统一响应：`{"code":0,"msg":"ok","data":{...}}`。错误码见 `ErrorCode`：
`1001 课程不存在` `1002 名额已满` `1003 重复选课` `1004 时间冲突` `1005 学分超限` `1006 系统繁忙（限流/抢锁失败）` `1007 未预热`。

---

## 6. 分阶段计划（30 小时）

每个 Stage 完成都要跑通「完成标志」，否则不进入下一 Stage。

### Stage 0 · 环境（1h）

- 本地起 MySQL / Redis / RabbitMQ（Docker Compose，见 `docker/docker-compose.yml`）
- 建库建表，导入 `sql/schema.sql`
- Maven 项目初始化，依赖拉齐

**完成标志**：`mvn -q compile` 通过；`curl localhost:8080/actuator/health` 返回 `UP`。

### Stage 1 · 骨架跑通（3h）

- 实体 + Mapper + 基础 CRUD
- 生成压测数据接口 `/api/admin/gen-data`
- `POST /api/selection` 的**朴素版本**：直接查库、判余量、`INSERT`，无锁无缓存（这是 v1 压测基线，**故意保留**）
- 全局异常处理 + 统一响应 + 结构化日志

**完成标志**：单个请求能选课成功；连续发 2 次同一请求第二次报"重复选课"。

### Stage 2 · 缓存层（6h）

- Caffeine L1（课程列表 60s）+ Redis L2（300s + 随机抖动防雪崩）
- 课程详情缓存，穿透用**缓存空值（TTL 60s）**，**不用布隆过滤器**（理由见第 9 节）
- 热点课程击穿防护：Redisson 锁互斥重建
- 预热：`/api/admin/warmup` 把课程信息 + 剩余名额 + 学生已占时段写入 Redis，用 Redisson 锁保证多实例只执行一次

**完成标志**：`redis-cli` 能看到 `course:cap:*`、`student:sched:*`；清掉 Redis 后重启，接口不报错（走缓存空值路径）。

### Stage 3 · 选课核心（8h）★ 技术重心

- **Lua 原子扣减**（课程维度）：`lua/deduct_course.lua`
- **Redisson 学生锁**（学生维度）：`lock:select:{studentId}`，包住冲突检测与写入
- 冲突检测：查该生已选课程的时段，与目标课程时段比对
- 学分上限校验
- 事务与锁的顺序（见第 7 节，这是必考坑）
- MQ 异步落库 + 幂等消费 + 死信

**完成标志**：并发 500 线程抢同一门 100 容量的课，无超卖、无重复、无冲突误判。

### Stage 4 · 削峰 + 限流（6h）

- Token Bucket Lua：`rl:select:{studentId}`，约 5 req/s
- **限流粒度按 studentId 而非 IP**（校园网 NAT 出口 IP 相同，按 IP 会误杀整栋宿舍楼）
- 超限快速失败，返回 `1006`

**完成标志**：单个学生连点 20 次，只有前几次成功，其余返回 1006。

### Stage 5 · 压测（4h）★ 不可砍

4 组对照，每组记录 QPS / P50 / P95 / P99 / 错误率 / 超卖数 / 冲突误判数：

| 版本 | 配置 |
|---|---|
| v1 | 直连 MySQL（Stage 1 的朴素版） |
| v2 | + Redis 缓存 + Lua 扣减 + 学生锁 |
| v3 | + MQ 异步落库 |
| v4 | + 限流 |

线程梯度：200 / 500 / 1000。

**可选对比实验（额外 2h，强烈建议做）**：实现方案 A（纯 Lua 全原子，把时段与学分也放进 Redis，去掉学生锁），压测对比 v2，得出"去掉锁换来多少 QPS、代价是什么"。**这组对比是全项目最有说服力的简历素材。**

### Stage 6 · 部署 + README（2h）

- Docker Compose 一键起（注意 2G 内存：`-Xmx512m`，RabbitMQ 限制内存）
- README 写明架构图、演进数据表、启动命令

---

## 7. 关键设计决策（Agent 必须遵守）

### 7.1 双粒度并发控制 ★ 本项目最大亮点

| 维度 | 手段 | 理由 |
|---|---|---|
| **课程维度**（容量扣减、判重） | Redis Lua 原子脚本，**无锁** | 一门课有上千学生同时抢，加课程锁会让所有人串行，QPS 崩掉 |
| **学生维度**（冲突检测、学分） | Redisson 锁，key = `lock:select:{studentId}` | 一个学生的选课操作本就该串行；且锁粒度与数据边界（该生已选课程）一致 |

**Agent 必须能在注释里说清"为什么两处手段不同"。这是面试区分度最高的一问。**

### 7.2 Lua 脚本（方案 B，Stage 3 主实现）

`resources/lua/deduct_course.lua`：

```lua
-- KEYS[1] = course:cap:{courseId}      剩余名额
-- KEYS[2] = course:selected:{courseId} 已选学生集合
-- ARGV[1] = studentId
-- 返回: 1 成功 / 0 已满 / -1 未预热 / -2 重复选课
local cap = tonumber(redis.call('GET', KEYS[1]))
if cap == nil then return -1 end
if cap <= 0 then return 0 end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -2 end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 1
```

注意：Lua 里的判断与写入是**同一线程内顺序执行**，中间不会被其他命令插入——这就是原子性的来源，不需要锁。

### 7.3 方案 A（纯 Lua 全原子，Stage 5 对比用）

`resources/lua/deduct_full.lua`：把时段集合与学分也纳入同一脚本。

```lua
-- KEYS[1] = course:cap:{courseId}
-- KEYS[2] = course:selected:{courseId}
-- KEYS[3] = student:sched:{studentId}   已占时段集合
-- KEYS[4] = student:credit:{studentId}  已选学分
-- ARGV[1] = studentId, ARGV[2] = credit, ARGV[3] = maxCredit, ARGV[4..] = 新课时段 slot
local cap = tonumber(redis.call('GET', KEYS[1]))
if cap == nil then return -1 end
if cap <= 0 then return 0 end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -2 end
local cur = tonumber(redis.call('GET', KEYS[4]) or '0')
if cur + tonumber(ARGV[2]) > tonumber(ARGV[3]) then return -3 end
for i = 4, #ARGV do
  if redis.call('SISMEMBER', KEYS[3], ARGV[i]) == 1 then return -4 end
end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
for i = 4, #ARGV do redis.call('SADD', KEYS[3], ARGV[i]) end
redis.call('INCRBY', KEYS[4], ARGV[2])
return 1
```

**方案 A 的风险必须在 README 里写明**：Redis 中的时段集合若丢失（重启未持久化），冲突检测会失效 → 需要启动时从 MySQL 重建。这正是"为什么主实现保留锁"的理由。

### 7.4 事务与锁的顺序（必考坑）

```java
// SelectionService：编排层，不加 @Transactional
public Result select(Long studentId, Long courseId) {
    if (!limiter.tryAcquire("rl:select:" + studentId)) {
        return Result.fail(ErrorCode.BUSY);
    }
    RLock lock = redissonClient.getLock("lock:select:" + studentId);
    boolean locked = false;
    try {
        // 洪峰下快速失败优于排队
        locked = lock.tryLock(500, 10_000, TimeUnit.MILLISECONDS);
        if (!locked) return Result.fail(ErrorCode.BUSY);

        // ★ 事务方法必须在另一个 Bean 中，同类内调用 @Transactional 不生效
        return selectionTxService.doSelect(studentId, courseId);
    } finally {
        if (locked && lock.isHeldByCurrentThread()) lock.unlock();
    }
}

// SelectionTxService：事务层
@Transactional(rollbackFor = Exception.class)
public Result doSelect(Long studentId, Long courseId) { ... }
```

**为什么锁必须在事务外层、且在事务提交后才释放**：
如果在 `@Transactional` 方法内部加锁，Spring 的事务是 AOP 代理，方法返回时锁先释放、事务后提交。此时另一个请求拿到锁，读到的还是未提交前的旧数据 → 冲突检测失效。

### 7.5 幂等与兜底

- MQ 消费端：`INSERT ... ON DUPLICATE KEY UPDATE` + 业务去重键（`sel:done:{studentId}:{courseId}`）
- 兜底：`selection` 表唯一索引 `uk_student_course`
- **面试话术**：分布式锁不是 100% 可靠（Redis 主从切换会丢锁），最终一致性靠数据库约束兜底

### 7.6 Redisson 锁的其他真实落点（不要硬造需求）

1. **热点课程缓存重建**（Stage 2）
2. **换课**（退 A 选 B，跨两个 course key，Lua 无法原子覆盖）— `POST /api/selection/change`
3. **候补补位**：退选触发补位时按课程串行，避免多个候补同时补位超卖（如做候补功能）
4. **预热任务多实例只执行一次**

---

## 8. AI 协作红线

**AI 可以放手做**：骨架代码、CRUD、配置类、DTO、压测脚本模板、Dockerfile、README 草稿。

**AI 不能替我做决定**（必须停下来问我）：

- Lua 脚本的并发语义
- 锁的粒度、加锁位置、与事务的相对顺序
- 压测结论的解读

原因很直接：**面试被追问的恰恰是这三项**。AI 生成的并发代码我会逐行看，看不懂的地方必须重写。

**其他约定**：

- 不要虚构压测数字，不写"支撑百万并发"这类不可验证的表述
- 不要引入未讨论过的依赖
- 每个 Stage 结束给验证命令，我跑通再继续
- 关键取舍在代码注释里写明理由，不写"优化性能"这种空话

---

## 9. 布隆过滤器：主链路不使用（已裁决）

**理由**：布隆的价值前提是键空间巨大且不可枚举。选课系统课程全集只有几百到几千条，**可全量缓存**，穿透流量用"缓存空值 + 短 TTL + 参数校验"即可挡住。硬加要付误判率、内存、维护成本，且布隆**不支持删除**（课程下架无法移除）。

**面试风险**：被问"为什么不用更简单的方案"时答不上来，这个关键词反而扣分。

**推荐做法（比堆上去更强）**：实现 → 压测对比 → 记录"课程全集 3000 条已全量缓存，穿透占比 <0.1%，QPS 无提升，故回滚"。**"加了、测了、否掉了"比"堆上了"更能证明不背八股**，且完全真实。

**唯一合理落点（P1 可选）**：选课资格白名单（专业/年级限制，名单几十万级）作 DB 查询前置过滤。布隆无假阴性，假阳性只多查一次 DB，安全。

---

## 10. 简历 bullet 预演（4 条，等实测数据填入）

1. 设计并实现高校选课系统，采用 **Caffeine + Redis 多级缓存**与**开放前预热**，课程查询 QPS 从 X 提升至 Y，P99 由 A ms 降至 B ms
2. 针对抢课洪峰设计**双粒度并发控制**：课程维度用 Redis Lua 原子扣减（无锁，避免全员串行），学生维度用 Redisson 分布式锁串行化冲突检测；1000 并发下**零超卖、零重复选课、零时间冲突**
3. 选课记录经 RabbitMQ **异步落库削峰**，消费端幂等 + 死信重试；结合 MySQL 唯一索引兜底，解决分布式锁在 Redis 主从切换下可能失效的一致性问题
4. 实现按学生维度的 Redis 令牌桶限流（校园网 NAT 出口 IP 相同，按 IP 会误杀）；JMeter 四组对照压测，输出 v1→v4 演进数据并 docker-compose 部署上线

---

## 11. 面试追问预埋（每 Stage 结束自查能否回答）

1. 为什么课程维度用 Lua 而不用锁？
2. 为什么学生维度又要用锁，不用 Lua 一次做完？
3. 锁加在事务内还是事务外？加错了会怎样？
4. 为什么 `doSelect` 要单独放一个 Bean？
5. Redis 主从切换丢锁怎么办？你的兜底是什么？
6. Redisson 看门狗机制是什么？为什么不指定 leaseTime？
7. 释放锁前为什么要判 `isHeldByCurrentThread`？
8. 为什么限流按 studentId 而不是 IP？
9. 缓存三兄弟（穿透/击穿/雪崩）你分别怎么处理的？
10. 为什么不用布隆过滤器？
11. MQ 消费失败了怎么办？消息会丢吗？
12. 异步落库后用户立刻查课表查不到，怎么解释？
13. 预热做了什么？不预热会怎样？
14. 超卖的边界在哪？唯一索引和 Redis 谁先兜底？
15. 你的压测数据是怎么测的？瓶颈最后定位在哪？

---

## 12. 红线（不可突破）

- 代码全部自己写或与 AI 结对完成，**每一行都要能讲清楚**
- 压测数字必须实测，不估算、不夸大
- 不写"精通"、"生产级"、"百万并发"
- 部署必须真实可用，简历上"已部署"三个字要经得起验证
