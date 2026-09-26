-- ============================================================
-- 方案 A（Stage 5 对比实验用）：全原子扣减，时段冲突 + 学分也放进 Lua，
-- 从而【去掉学生锁】。来源：蓝图 7.3，语义已裁决。
--
-- 与方案 B 的对比是本项目的核心实验：
--   收益 = 少一次 tryLock/unlock 的 RTT 与锁竞争，QPS 提升多少要实测；
--   代价 = Redis 成为冲突检测的唯一事实来源，student:sched:* 若丢失
--          （重启未持久化）冲突检测整体失效，必须启动时从 MySQL 重建。
--   这正是"为什么主实现保留锁"的答案：锁方案的冲突检测可随时回源 DB 重建，
--   对 Redis 数据完整性的依赖更低。
--
-- KEYS[1] = course:cap:{courseId}
-- KEYS[2] = course:selected:{courseId}
-- KEYS[3] = student:sched:{studentId}   已占时段集合 (SET, 元素 "day:period")
-- KEYS[4] = student:credit:{studentId}  已选学分 (STRING)
-- ARGV[1] = studentId, ARGV[2] = 本课学分, ARGV[3] = 学生学分上限,
-- ARGV[4..] = 本课展开后的时段 slot 列表
-- 返回: 1 成功 / 0 已满 / -1 未预热 / -2 重复 / -3 学分超限 / -4 时间冲突
-- ============================================================

local cap = tonumber(redis.call('GET', KEYS[1]))
if cap == nil then return -1 end
if cap <= 0 then return 0 end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -2 end

local cur = tonumber(redis.call('GET', KEYS[4]) or '0')
if cur + tonumber(ARGV[2]) > tonumber(ARGV[3]) then return -3 end

-- 时段冲突 = 学生已占集合与新课时段有交集。逐节展开成离散点位后，
-- 区间重叠判断退化为 SISMEMBER，O(课时数) 次 O(1) 查询
for i = 4, #ARGV do
  if redis.call('SISMEMBER', KEYS[3], ARGV[i]) == 1 then return -4 end
end

-- 全部检查通过后才写入（同一脚本内，检查与写入之间无竞态窗口）
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
for i = 4, #ARGV do redis.call('SADD', KEYS[3], ARGV[i]) end
redis.call('INCRBY', KEYS[4], ARGV[2])
return 1
