# 高校选课系统（course-selection）

> 高并发下的并发正确性：超卖、重复选课、时间冲突、学分超限，四个问题一个都不能出现。
> 单体 Spring Boot 3 · 30 小时工期 · 所有性能数字均为 JMeter 实测。

## 技术栈

JDK 17 · Spring Boot 3.2 · MyBatis-Plus 3.5 · MySQL 8 · Redis（Lua 原子扣减）· Caffeine（L1）· Redisson（学生锁）· RabbitMQ（异步落库）· JMeter 5.6 · Docker Compose

## 核心设计（一句话版，详见 [course-selection-blueprint.md](course-selection-blueprint.md)）

- **双粒度并发控制**：课程维度 Redis Lua 原子扣减（无锁，避免全员串行）；学生维度 Redisson 锁（粒度与数据边界一致）
- **事务与锁的顺序**：锁在 `@Transactional` 外层，事务方法独立 Bean（`SelectionTxService`）
- **兜底链**：Lua 判重 → 学生锁 → `uk_student_course` 唯一索引（锁不是 100% 可靠）
- **削峰**：RabbitMQ 异步落库 + 消费幂等 + 死信；按 studentId 令牌桶限流（校园网 NAT，按 IP 会误杀整栋宿舍楼）
- **不用布隆过滤器**：课程全集可枚举、全量缓存 + 空值短 TTL 即可——加了、测了、否掉了

## 压测演进数据（Stage 5 填写，只填实测值）

| 版本 | 配置 | 并发 | QPS | P50 | P95 | P99 | 错误率 | 超卖数 |
|---|---|---|---|---|---|---|---|---|
| v1 | 直连 MySQL 朴素版 | 200/500/1000 | - | - | - | - | - | - |
| v2 | + 缓存 + Lua 扣减 + 学生锁 | 200/500/1000 | - | - | - | - | - | - |
| v3 | + MQ 异步落库 | 200/500/1000 | - | - | - | - | - | - |
| v4 | + 令牌桶限流 | 200/500/1000 | - | - | - | - | - | - |

方案 A（纯 Lua 全原子，去学生锁）vs 方案 B 对比：_待 Stage 5 实测_

## 本地启动（Windows，非 Docker）

```bash
# 1. MySQL：本机 MySQL80 服务；建库导表
mysql -uroot -p < sql/schema.sql
# 2. Redis：本机原生 Redis
cd /d/develop/Redis && ./redis-server.exe redis.windows.conf &
# 3. 填好 application.yml 中 MYSQL_PASSWORD 后启动
mvn spring-boot:run
curl localhost:8080/actuator/health
```

服务器部署（Docker Compose）见 `docker/`，Stage 6 完成。

## 开发文档

- [course-selection-blueprint.md](course-selection-blueprint.md) — 完整规格（DDL / 接口 / Lua / 验收标准）
- [DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md) — 7 天逐日执行指南
- [NOTES.md](NOTES.md) — 问题 / 反思 / 优化记录
- [sql/schema.sql](sql/schema.sql) — 建表脚本（含索引设计理由）
