-- ============================================================
-- 高校选课系统 · 建库建表脚本（蓝图第 4 节）
-- 执行方式（Day 1）：mysql -uroot -p < sql/schema.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS course_selection
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE course_selection;

-- 主键说明：所有表用 BIGINT 主键但【不加 AUTO_INCREMENT】，
-- ID 由 MyBatis-Plus 雪花算法（id-type: assign_id）在应用侧生成。
-- 理由：压测走 MQ 异步落库时，消费端先拿到 ID 再插入，不依赖数据库自增回写；
-- 且后续若分库分表，自增 ID 会冲突。（面试可讲：为什么不用自增主键）

CREATE TABLE IF NOT EXISTS student (
  id           BIGINT PRIMARY KEY,
  student_no   VARCHAR(32) NOT NULL UNIQUE,
  name         VARCHAR(64) NOT NULL,
  grade        VARCHAR(16),
  major        VARCHAR(64),
  max_credit   INT NOT NULL DEFAULT 30,
  created_at   DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS course (
  id             BIGINT PRIMARY KEY,
  course_code    VARCHAR(32) NOT NULL UNIQUE,
  name           VARCHAR(128) NOT NULL,
  teacher        VARCHAR(64),
  credit         INT NOT NULL,
  capacity       INT NOT NULL,
  selected_count INT NOT NULL DEFAULT 0,   -- 由 MQ 消费端累加，运行期以 Redis 为准
  status         TINYINT NOT NULL DEFAULT 1, -- 1 开放 0 关闭
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- 一门课可占多个时段；时段用「周几 + 起始节次 + 结束节次」表达
CREATE TABLE IF NOT EXISTS course_schedule (
  id           BIGINT PRIMARY KEY,
  course_id    BIGINT NOT NULL,
  day_of_week  TINYINT NOT NULL,  -- 1-7
  start_period TINYINT NOT NULL,  -- 1-12
  end_period   TINYINT NOT NULL,
  INDEX idx_course (course_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS selection (
  id          BIGINT PRIMARY KEY,
  student_id  BIGINT NOT NULL,
  course_id   BIGINT NOT NULL,
  status      TINYINT NOT NULL DEFAULT 1,  -- 1 已选  0 已退
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  -- ★ uk_student_course 是并发正确性的最后防线，不是可选项：
  --   Redis 锁在主从切换时可能丢失（Redisson 看门狗也救不了进程崩溃+failover 的组合），
  --   一旦锁失效导致两个"选同一门课"的请求并发写入，唯一索引让第二条 INSERT 直接失败。
  --   兜底顺序：Lua 原子判重(第一道) → 学生锁串行化(第二道) → 唯一索引(最后防线)。
  UNIQUE KEY uk_student_course (student_id, course_id),
  -- 冲突检测要按学生查已选课程（SELECT ... WHERE student_id = ?），必须走索引
  INDEX idx_student (student_id),
  INDEX idx_course (course_id)
) ENGINE=InnoDB;

-- 测试数据（5000 学生 × 50 门课 × 每门 100 容量）不在这里插，
-- 由 Stage 1 的 POST /api/admin/gen-data 接口生成（顺便是对批量插入的练习）。
