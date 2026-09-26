# 7 天开发指南（30 小时 · 9/26 - 10/2）

> 配套文件：`course-selection-blueprint.md`（规格）、`NOTES.md`（问题/反思记录）。
> 原则：**每天一个可验证的里程碑，验证不过不收工**；压测（Day 6）绝对不可砍。

---

## 每日固定流程（照着走，不用每天想"今天干嘛"）

1. **开工（5 min）**：看本指南当天章节 → 启动 MySQL 服务（已自启）+ Redis（命令见 Day 1）
2. **结对编码**：在 VSCode 中把「蓝图开场白 + 当天 Stage 任务」发给 AI Agent（每天的"给 Agent 的任务"小节可直接复制）
3. **验证（不可跳过）**：跑完当天「验证命令」，全绿才算完成
4. **收工四件套（15 min）**：
   - `git add -A && git commit -m "stageN: 一句话"`
   - 到达打标点则 `git tag vX-xxx`
   - `NOTES.md` 记录：今天踩的坑 / 学到的点 / 没解决的问题
   - 口头回答当天「面试自查题」（答不出 → 记入 NOTES.md 待办，明天补）

**给 Agent 的任务里永远带上蓝图红线**：一次只做一个 Stage、并发代码必须写"为什么"注释、涉及 Lua 语义/锁粒度/压测解读必须停下来问你。

---

## 总览

| Day | 日期 | Stage | 时长 | 里程碑（验证物） | git tag |
|---|---|---|---|---|---|
| 1 | 9/26 | S0 + S1 | 4h | health UP；朴素选课成功/重复拒绝；5000 学生数据入库 | `v1-baseline` |
| 2 | 9/27 | S2 前半 | 4h | L1+L2 缓存生效，空值防穿透，TTL 带抖动 | - |
| 3 | 9/28 | S2 后半 + S3 预习 | 4h | warmup 后 redis-cli 可见 `course:cap:*`/`student:sched:*`；**逐行看懂 deduct_course.lua** | `v2-cache-warmup` |
| 4 | 9/29 | S3 核心 | 5h | 500 线程抢 100 容量：零超卖、零重复、零冲突误判 | `v2-lock-lua`（=压测 v2 状态） |
| 5 | 9/30 | S3 MQ + S4 限流 | 5h | 消息落库幂等、死信可见；单学生连点 20 次仅前几次通过 | `v3-mq`、`v4-ratelimit` |
| 6 | 10/1 | S5 压测 | 5-6h | v1-v4 × 200/500/1000 数据表 + 方案 A/B 对比结论 | `v5-fulllua`（实验分支） |
| 7 | 10/2 | S6 + 复盘 | 4h | 部署真实可用；README 数据表填完；15 问全部能答 | `v1.0` |

---

## Day 1（今天）· Stage 0 环境 + Stage 1 朴素版

### 我已帮你做完的

- Git 仓库初始化、`.gitignore`、首次提交
- Maven 骨架：pom（依赖已拉齐锁版本）、application.yml、启动类
- 通用层：Result / ErrorCode(1001-1007) / BizException / GlobalExceptionHandler / CacheKeys
- 实体 + Mapper 四张表齐、schema.sql、三个 Lua 脚本文件、docker/、README 骨架
- 本机 Redis 已启动验证（密码见 application-local.yml）

### 你要做的（按序）

- [ ] **1. 填 MySQL 密码**：编辑 `src/main/resources/application-local.yml`，把 `你的密码` 换成本机 MySQL root 密码（该文件不进 Git）
- [ ] **2. 建库导表**：
  ```bash
  mysql -uroot -p < sql/schema.sql
  mysql -uroot -p -e "USE course_selection; SHOW TABLES;"   # 应看到 4 张表
  ```
- [ ] **3. 确认 Redis 在跑**（今天我已启动；以后开机自己起）：
  ```bash
  cd /d/develop/Redis && ./redis-server.exe redis.windows.conf &   # 启动
  ./redis-cli.exe -a luohaoyu123 ping                              # 应返回 PONG
  ```
- [ ] **4. 编译 + 启动 + 健康检查**（Stage 0 完成标志）：
  ```bash
  mvn -q compile
  mvn spring-boot:run
  curl localhost:8080/actuator/health    # {"status":"UP"}
  ```
- [ ] **5. VSCode 里让 Agent 做 Stage 1**（任务单见下），你负责看懂每一行 + 跑验证

### 给 Agent 的 Stage 1 任务单（可复制）

```
做 Stage 1（蓝图第 6 节），骨架/实体/Mapper/通用层已存在，不要重建：
1. POST /api/admin/gen-data：生成 5000 学生 × 50 门课（每门容量 100、学分 1-4 随机、
   随机 1-2 个时段）；批量插入（MyBatis-Plus saveBatch 或 XML foreach），接口幂等
   （重复调用先清空或跳过，写明你选哪种及理由）
2. POST /api/selection 朴素版：直接查库判余量 + 查重 + INSERT，无任何锁/缓存/事务优化。
   ★ 这是 v1 压测基线，故意写得朴素，但要在类注释里写明"这版在并发下会超卖，
   竞态窗口在查余量和写入之间"——Stage 5 压测要用它做对照
3. GET /api/selection/mine?studentId=：返回该生已选课程（联表 course）
4. 结构化日志：选课成功/失败都打 studentId、courseId、结果码
完成后给我 curl 验证命令。不要提前做任何 Stage 2+ 的内容（缓存/Lua/锁/MQ 都不要碰）。
```

### 验证命令（Stage 1 完成标志）

```bash
curl -X POST localhost:8080/api/admin/gen-data
# 从返回或 DB 里拿一个真实的 studentId/courseId 替换下面的 1
curl -X POST localhost:8080/api/selection -H "Content-Type: application/json" -d '{"studentId":1,"courseId":1}'
# 期望 code=0
curl -X POST localhost:8080/api/selection -H "Content-Type: application/json" -d '{"studentId":1,"courseId":1}'
# 期望 code=1003 重复选课
curl "localhost:8080/api/selection/mine?studentId=1"
mysql -uroot -p -e "SELECT COUNT(*) FROM course_selection.student; SELECT COUNT(*) FROM course_selection.course;"
```

### 收工

```bash
git add -A && git commit -m "stage1: naive selection + gen-data (v1 baseline)"
git tag v1-baseline
```

**面试自查题**：① 朴素版在 500 并发下为什么必然超卖？画出两个请求交错的时间线。② 为什么 `uk_student_course` 挡得住重复选课，却挡不住超卖？（提示：超卖不违反唯一约束）

---

## Day 2 · Stage 2 前半：多级缓存

### 任务

- [ ] Caffeine L1（课程列表 60s）+ Redis L2（300s + **随机抖动**防雪崩，抖动范围自己定并写理由）
- [ ] `GET /api/courses`、`GET /api/courses/{id}` 走两级缓存
- [ ] 穿透防护：**缓存空值 TTL 60s** + 参数校验；**不用布隆**（蓝图第 9 节裁决，能复述理由）

### 给 Agent 的任务单要点

```
做 Stage 2 的缓存部分（蓝图 Stage 2 前三条）。CacheConfig：Caffeine 手动构建
（不用 spring-cache 注解，显式 get/put，方便讲清两级缓存的读写顺序）。
读路径：L1 → L2 → DB；写路径：本阶段没有写课程的需求，只考虑读。
每个 TTL 写注释说明为什么是这个值、抖动防的是什么。不要做预热和击穿锁（明天做）。
```

### 验证

```bash
curl localhost:8080/api/courses                 # 第一次慢（回源），第二次毫秒级
curl localhost:8080/api/courses/999999          # 不存在的课 → 1001，且第二次请求不回源（看日志）
/d/develop/Redis/redis-cli.exe -a luohaoyu123 keys "course:detail:*"   # 有空值缓存
# L1 生效证明：连续两次请求，第二次日志里没有 Redis 查询
```

**面试自查题**：① 穿透/击穿/雪崩分别是什么，你今天解决了哪个？② 为什么 L1 是 60s 而 L2 是 300s（L1 短于 L2 的道理）？③ 布隆过滤器为什么不用？

---

## Day 3 · Stage 2 后半：预热 + 击穿防护 + Stage 3 预习

### 任务

- [ ] `POST /api/admin/warmup`：课程信息 + 剩余名额(`course:cap:*`) + 学生已占时段(`student:sched:*`) + 学分(`student:credit:*`) 写入 Redis；**Redisson 锁保证多实例只执行一次**
- [ ] 热点课程击穿：缓存重建用 Redisson 互斥锁（`lock:rebuild:course:{id}`）
- [ ] **预习（1h，不写代码）**：逐行读懂 `deduct_course.lua`，在 NOTES.md 用自己的话写出：原子性从哪来、每个返回码对应什么错误、为什么不加锁

### 验证（Stage 2 完成标志）

```bash
curl -X POST localhost:8080/api/admin/warmup
/d/develop/Redis/redis-cli.exe -a luohaoyu123 --scan --pattern "course:cap:*" | head
/d/develop/Redis/redis-cli.exe -a luohaoyu123 GET course:cap:1        # 应为 100
/d/develop/Redis/redis-cli.exe -a luohaoyu123 SCARD course:selected:1  # 应为已选人数
# 清掉 Redis 重启应用，GET /api/courses/1 不报错（走空值路径），再 warmup 恢复
```

```bash
git tag v2-cache-warmup
```

**面试自查题**：① 预热做了什么？不预热直接开放选课会怎样？② warmup 的 Redisson 锁和选课的学生锁，作用有什么本质不同？

---

## Day 4 · Stage 3 核心：Lua 扣减 + 学生锁 + 事务顺序 ★ 全项目技术重心

### 任务

- [ ] `SelectionLua`：加载 `deduct_course.lua`（DefaultRedisScript，脚本内容你已在 Day 3 读懂）
- [ ] `SelectionService`（编排层，**无 @Transactional**）：限流占位 → 学生锁 `lock:select:{studentId}` → 调 `SelectionTxService`
- [ ] `SelectionTxService`（**独立 Bean**，@Transactional）：冲突检测（该生已选课程时段 vs 目标课程时段）+ 学分校验 + 落库
- [ ] **锁在事务外层、事务提交后才释放**——蓝图 7.4，把"加错了会怎样"写进注释
- [ ] 本 Stage 先**同步写库**（`selection.async-db=false`），MQ 明天接

### 给 Agent 的任务单要点

```
做 Stage 3 的锁+Lua+事务部分（蓝图 7.1/7.2/7.4，MQ 不做）。严格按蓝图 7.4 的代码结构：
SelectionService 编排（锁在事务外层），SelectionTxService 独立 Bean 承载 @Transactional。
冲突检测的时段数据源：查 DB（course_schedule 联 selection），不依赖 Redis 的 student:sched
（那是方案 A 的数据源，本周六对比实验才用）。每处锁/事务边界写"为什么"注释。
Lua 返回码 → ErrorCode 映射在 SelectionLua 里集中做。
```

### 验证（Stage 3 阶段性完成标志，正式压测在 Day 6）

```bash
# 手工并发冒烟：50 并发抢同一门课（正式 500 线程在 Day 6 用 JMeter）
# 用 redis-cli 把某课容量调小便于验证：SET course:cap:2 5
# 验证三件事：
mysql -uroot -p -e "SELECT course_id, COUNT(*) c FROM course_selection.selection GROUP BY course_id HAVING c > 100;"   # 空 = 无超卖
mysql -uroot -p -e "SELECT student_id, course_id, COUNT(*) c FROM course_selection.selection GROUP BY student_id, course_id HAVING c > 1;"  # 空 = 无重复
# 时间冲突用例：给某生选两门同时段的课，第二次应 1004
```

```bash
git tag v2-lock-lua    # 压测 v2 状态 = 缓存 + Lua + 锁，同步落库
```

**面试自查题（今天最重要）**：① 为什么课程维度用 Lua 不用锁、学生维度用锁不用 Lua？② 锁加在 @Transactional 内部会发生什么（完整推演）？③ 为什么 doSelect 要独立 Bean？④ tryLock 的 waitTime/leaseTime 各是什么，看门狗何时生效？⑤ unlock 前为什么判 isHeldByCurrentThread？

---

## Day 5 · Stage 3 MQ 异步落库 + Stage 4 限流

### 任务

- [ ] **上午先装 RabbitMQ**（约 30 min）：
  ```bash
  winget install Erlang.ErlangOTP
  winget install RabbitMQ.RabbitMQ    # 装完重开终端
  rabbitmqctl status                  # 确认在跑；管理台插件: rabbitmq-plugins enable rabbitmq_management → localhost:15672 (guest/guest)
  ```
  装好后把 application.yml 的 `management.health.rabbit.enabled` 改回 `true`。
  **winget 装不上就停下来告诉我，不要硬耗**（备选：暂缓 MQ，Day 6 先压 v1/v2/v4）。
- [ ] MQ：选课成功后发消息 → 消费端落库（`INSERT ... ON DUPLICATE KEY UPDATE` 幂等）+ `sel:done:*` 去重键 + 死信队列；`selection.async-db=true` 开关切换同步/异步
- [ ] Stage 4 限流：`TokenBucketLimiter` + `token_bucket.lua`（**参考实现已放好，先逐行看懂，参数自己定并写理由**），按 studentId 限流；加开关 `selection.rate-limit.enabled`（压测 v3/v4 切换用）
- [ ] 想清楚并在注释写明：**异步落库后用户立刻查课表查不到怎么办**（面试第 12 问）

### 验证

```bash
# MQ 链路：选一门课 → 15672 管理台看到消息入队/消费 → DB 有记录
# 幂等：手动往队列重发同一条消息（管理台 publish），DB 不出现第二行
# 死信：停应用 → publish 一条坏消息 → 起应用 → 死信队列可见
# 限流：单学生 1 秒内连发 20 次
for i in $(seq 20); do curl -s -X POST localhost:8080/api/selection -H "Content-Type: application/json" -d '{"studentId":3,"courseId":5}' & done; wait
# 期望：前几次是业务码（0/1002/1003...），其余大量 1006
```

```bash
git tag v3-mq           # async-db=true、限流关
git tag v4-ratelimit    # 限流开
```

**面试自查题**：① MQ 消费失败消息会丢吗？你的重试/死信链路？② 为什么限流按 studentId 不按 IP？③ 令牌桶 vs 固定窗口的区别？④ Redis 主从切换丢锁，你的兜底链条完整说一遍。

---

## Day 6 · Stage 5 压测 ★ 不可砍，简历数据的唯一来源

### 准备（我会在 Day 5 晚上/Day 6 早上帮你生成 jmx 模板）

- JMeter 已装：`d:\develop\apache-jmeter-5.6.3`
- 4 组版本靠 **git tag 切换 + 配置开关**：v1(`git checkout v1-baseline`)、v2(`v2-lock-lua`)、v3(`v3-mq`)、v4(`v4-ratelimit`)
- 每组跑 200 / 500 / 1000 线程梯度；**每组压测前**：清 Redis → warmup → 清 selection 表 → gen-data 重置，保证初始状态一致（写进压测记录，这是数据可信度的一部分）

### 数据记录表（每组一行，实测值，进 README 和简历）

| 版本 | 线程 | QPS | P50 | P95 | P99 | 错误率 | 超卖数 | 冲突误判数 | 备注 |
|---|---|---|---|---|---|---|---|---|---|

超卖数/冲突误判数用 Day 4 的 SQL 查；错误率按业务码分类统计（1002 是正常拒绝不算错误，500 才算）。

### 方案 A/B 对比实验（强烈建议，+2h，简历最有说服力的素材）

- 基于 v4 建分支：把冲突检测/学分改走 `deduct_full.lua`，去掉学生锁 → tag `v5-fulllua`
- 同条件压测，对比 v2：QPS 差多少？代价（Redis 数据丢失时冲突检测失效、需启动重建）写进 README
- **结论解读你自己来**（红线），我提供数据整理模板

### 瓶颈定位（面试第 15 问的素材）

压测时用任务管理器/`jconsole` 看：CPU 满了？MySQL 连接池等待？Redis 单线程打满？记录"最后的瓶颈在哪"——比数据本身更能体现工程能力。

---

## Day 7 · Stage 6 部署 + README + 面试复盘

### 任务

- [ ] 部署决策（到时问我，二选一）：**A.** 本机装 WSL2 + Docker Desktop 跑 docker compose（一次性成本 1-2h，简历可写"docker-compose 部署"）；**B.** 云服务器（若你有/愿意买 2G 轻量服务器，部署公网可访问，含金量最高）。**没真实部署就不写"已部署"**
- [ ] README 补完：架构图（文字版即可）、压测数据表（Day 6 实测值）、启动命令、方案 A 风险说明
- [ ] **15 问全部自答**（蓝图第 11 节），答案要点写进 NOTES.md；答不上的回代码里重新看
- [ ] 产出简历 4 条 bullet（蓝图第 10 节模板 + 实测数字），存到 WorkBuddy 供拼简历

### 收工

```bash
git tag v1.0
```

---

## 落后了怎么办（从下往上砍，压测不动）

1. 先砍：候补/换课接口（本来就是可砍项，目前没排进 7 天）
2. 再砍：Day 6 的方案 A/B 对比（可惜，但保 v1-v4 主数据优先）
3. 再砍：Stage 4 的死信/幂等精细化（保留基本落库）
4. **绝不砍**：Day 6 压测、Day 4 锁+事务正确性——这两个是项目存在的意义

每天实际用时超预算 1h 以上 → 当晚记 NOTES.md 并告诉我，我帮你重排后三天。
