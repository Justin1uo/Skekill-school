-- ============================================================
-- 方案 B（Stage 3 主实现）：课程维度 判重 + 扣减，单脚本原子完成
-- 来源：蓝图 7.2，语义已裁决，不要改动逻辑；学习任务是逐行看懂
--
-- KEYS[1] = course:cap:{courseId}      剩余名额 (STRING)
-- KEYS[2] = course:selected:{courseId} 已选学生集合 (SET)
-- ARGV[1] = studentId
-- 返回: 1 成功 / 0 已满 / -1 未预热 / -2 重复选课
-- ============================================================

local cap = tonumber(redis.call('GET', KEYS[1]))
-- 名额 key 不存在 = 没预热。宁可拒绝也不回源 DB：
-- 洪峰下回源会把 MySQL 打穿，1007 让客户端重试即可
if cap == nil then return -1 end
if cap <= 0 then return 0 end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -2 end

-- ★ 原子性的来源：Redis 单线程执行 Lua，上面三行判断与下面两行写入
-- 之间不可能插入其他请求的命令——"检查再执行"(check-then-act) 的
-- 竞态窗口被整个消掉了，所以课程维度【不需要锁】。
-- 若拆成 Java 里 GET→判断→DECR 三步，两个并发请求会同时看到 cap=1，
-- 双双 DECR 成功 → 超卖。这就是 v1 朴素版必然超卖的原因。
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 1
