-- ============================================================
-- 令牌桶限流（Stage 4）—— ★ 这是参考实现，不是已裁决的方案。
-- 按蓝图红线：Lua 并发语义由你决定。Day 5 动手前先逐行看懂，
-- 参数（桶容量 / 速率）自己定并在 NOTES.md 里写下选择理由。
--
-- 为什么令牌桶而不是固定窗口计数器：
--   固定窗口在窗口边界有 2 倍突刺问题；令牌桶允许"攒桶"应对
--   选课页刚打开时的连点，同时长期速率被 rate 约束。
--
-- KEYS[1] = rl:select:{studentId}   HASH{tokens, ts}
-- ARGV[1] = capacity  桶容量（允许的突发量，建议 5-10）
-- ARGV[2] = rate      每秒补充令牌数（蓝图建议约 5 req/s）
-- ARGV[3] = now_ms    当前毫秒时间戳
-- 返回: 1 放行 / 0 限流
-- ============================================================

-- ★ 时间戳由调用方传入而不是脚本内 redis.call('TIME')：
-- 含非确定性命令的脚本在主从复制/集群下有回放问题，
-- 传参是标准做法（副作用：依赖应用服务器时钟，单机部署无影响）
local bucket   = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens   = tonumber(bucket[1])
local ts       = tonumber(bucket[2])
local capacity = tonumber(ARGV[1])
local rate     = tonumber(ARGV[2])
local now      = tonumber(ARGV[3])

-- 桶不存在（首次请求或已过期）：给满桶，让页面打开时的正常连点通过
if tokens == nil then
  tokens = capacity
  ts = now
end

-- 按流逝时间补令牌，不超过桶容量
local delta = math.max(0, now - ts)
tokens = math.min(capacity, tokens + delta * rate / 1000)

local allowed = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
-- 桶满后约 1s 过期：5000 学生 = 5000 个 key，洪峰过后自动回收内存
redis.call('PEXPIRE', KEYS[1], math.ceil((capacity - tokens) / rate * 1000) + 1000)
return allowed
