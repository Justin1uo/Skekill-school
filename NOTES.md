# 项目笔记 · 问题 / 反思 / 优化

> 用法：每天收工前 15 分钟填写。踩坑必须记「根因」而不是只记「怎么绕过的」——面试讲踩坑经历时，根因才是加分项。
> 分区：📌 问题记录 ｜ 💭 反思 ｜ ⚡ 优化待办 ｜ 🧾 环境与账号备忘 ｜ 🎤 面试 15 问答案要点（Day 7 填）

---

## 📌 问题记录

| 日期 | 阶段 | 问题 | 根因 | 解决 | 状态 |
|---|---|---|---|---|---|
| 9/26 | S0 | 蓝图假设 Docker Compose 起中间件，但本机没有 Docker 也没装 WSL | Windows 家庭版装 WSL2+Docker Desktop 一次性成本高，且非项目核心 | 决策：开发期用本机原生 MySQL80 服务 + 原生 Redis（d:\develop\Redis）；Docker 只留给 Stage 6 部署（届时再决策 WSL2 或云服务器） | ✅ |
| 9/26 | S0 | Redis 启动后 ping 返回 NOAUTH | redis.windows.conf 里设了 requirepass | 密码写入 application-local.yml（已 gitignore），CLI 操作带 `-a` | ✅ |
| 9/26 | S0 | 本机 JDK 是 21，蓝图锁 JDK 17 | - | pom 里 `<java.version>17</java.version>`：JDK 21 编译器用 release=17 目标，产物与 JDK 17 语义一致，无需另装 | ✅ |
| 9/26 | S0 | 本机原生 Redis 是 3.2.100（微软 Windows 移植版），蓝图目标 7.x | 项目用到的命令（EVAL/GET/DECR/SADD/SISMEMBER/HSET/PEXPIRE）与 Redisson(3.0+)/Lettuce 均兼容 3.2，已实测 EVAL+SADD/SISMEMBER 通过 | 开发期先用 3.2；部署用 Docker redis:7-alpine。若 Day 4 锁相关出现莫名报错，备选方案：换 tporadowski Redis 5.0.14 Windows 版（10 分钟迁移） | 🔄 观察 |
| | | | | | |

<!-- 模板（复制上面一行填写）：
| 日期 | S? | 现象 | 根因 | 怎么解决的 | ✅/🔄 |
-->

## 💭 反思

- 9/26：选型阶段两次推翻（seckill → shortlink → 自建）说明"先看约束再选方案"——30h 工期约束下，fork 大项目的隐性成本（环境坑）远大于自建骨架。**教训：工期紧张时，可控性 > 现成功能。**
- （每天至少一条：今天哪个决策花了最长时间？值不值？）

## ⚡ 优化待办（想到就记，别打断当天主线）

- [ ] P1：布隆过滤器否定实验——实现→压测→记录"穿透占比 <0.1%、QPS 无提升故回滚"（蓝图第 9 节，简历素材）
- [ ] P1：微服务拆分（Nacos + Gateway）
- [ ] P2：候补/换课接口（Redisson 锁的额外真实落点）
- [ ] P2：选课资格白名单（布隆的唯一合理落点，几十万级）

## 🧾 环境与账号备忘（本机，非机密的可写这里）

| 项 | 值 |
|---|---|
| MySQL | 本机服务 `MySQL80`（8.0），root 密码在 application-local.yml |
| Redis | `d:\develop\Redis`，**3.2.100**，启动：`./redis-server.exe redis.windows.conf`，密码见 application-local.yml，CLI：`redis-cli.exe -a <密码>` |
| RabbitMQ | 未装，Day 5 用 winget 装（Erlang.ErlangOTP + RabbitMQ.RabbitMQ） |
| JMeter | `d:\develop\apache-jmeter-5.6.3`（与蓝图要求 5.6 一致，免装） |
| JDK / Maven | JDK 21（编译目标 17）/ Maven 3.9.15 |
| Docker | ❌ 未装（WSL 也未装），Stage 6 部署时决策 |
| Git 打点 | v1-baseline → v2-cache-warmup → v2-lock-lua → v3-mq → v4-ratelimit → v5-fulllua → v1.0 |

## 🎤 面试 15 问答案要点（Day 7 填，问题清单见蓝图第 11 节）

1. 为什么课程维度用 Lua 而不用锁？——（Day 4 后填）
2. ……
