# CLAUDE.md · 本项目 AI 协作规则

> 任何 AI 会话在本目录工作前，先读 `course-selection-blueprint.md`（完整规格）与 `DEVELOPMENT_GUIDE.md`（当前进度看 git log / tag）。

## 项目一句话

求职简历项目：高并发高校选课系统（单体 Spring Boot 3，30h 工期，7 天分 Stage 推进）。核心卖点是**高并发下的并发正确性**（超卖/重复选课/时间冲突/学分超限零容忍）+ **JMeter 实测压测演进数据**，不是业务功能。

## 协作铁律（来自项目主人，不可违反）

1. **一次只做一个 Stage**，完成即停，给出可执行验证命令（curl/SQL/redis-cli），等用户验证通过再继续。不要一次性生成整个项目。
2. **并发相关代码**（Lua、锁、事务边界、MQ 幂等、限流）必须写"为什么必须这样做"的注释；**Lua 并发语义、锁粒度与事务顺序、压测结论解读——这三项 AI 只能提供方案和参考，最终裁决权在用户**，遇到取舍停下来问。
3. **不虚构**：压测数字必须实测，不写"支撑百万并发/生产级/精通"；不引入未讨论过的依赖；不为实现方便简化并发正确性。
4. 技术栈版本已锁定（见蓝图第 2 节），不擅自升级。
5. 真实密码只进 `application-local.yml`（已 gitignore），任何要提交的文件不得含真实密码。

## 关键设计裁决（已定，勿翻案；细节见蓝图第 7 节）

- 双粒度并发控制：课程维度 Redis Lua 无锁原子扣减；学生维度 Redisson 锁 `lock:select:{studentId}`
- 锁在 @Transactional 外层，事务方法独立 Bean（SelectionTxService，同类内调用注解不生效）
- 布隆过滤器主链路不用（课程全集可枚举）；穿透 = 空值缓存 + 短 TTL
- `uk_student_course` 唯一索引是最后防线，不是可选项
- 限流按 studentId 不按 IP（校园网 NAT）
- Redis key 命名统一在 `CacheKeys.java`，禁止散落硬编码

## 本机环境事实（2026-09-26 盘点）

- Windows 11；JDK 21（pom 编译目标 17）；Maven 3.9.15；Git 2.54
- MySQL 8.0 = 本机服务 `MySQL80`（非 Docker）；Redis = `d:\develop\Redis` 原生 Windows 版（开机需手动启动，密码见 application-local.yml）
- **Docker / WSL 未安装**——不要给出依赖 Docker 的本地开发步骤；部署（Stage 6）时再决策
- RabbitMQ 未装（Day 5 winget 安装）；JMeter 在 `d:\develop\apache-jmeter-5.6.3`
- redis-cli 用法：`/d/develop/Redis/redis-cli.exe -a <密码>`；mysql CLI 可用

## 当前进度快照

- [x] Stage 0：骨架 + 通用层 + 实体/Mapper + schema.sql + Lua 脚本文件 + docker/（2026-09-26 由 Claude Code 搭建）
- [ ] Stage 1：gen-data + 朴素选课（v1 基线）+ /mine —— 用户在 VSCode 与 Agent 结对完成
- 进度以 git tag 为准：v1-baseline → v2-cache-warmup → v2-lock-lua → v3-mq → v4-ratelimit → v5-fulllua → v1.0
