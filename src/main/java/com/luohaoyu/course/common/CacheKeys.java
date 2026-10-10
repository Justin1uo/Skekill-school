package com.luohaoyu.course.common;

/**
 * Redis key 统一注册表（蓝图第 7 节出现的所有 key 都在这里，禁止散落硬编码）。
 *
 * 集中管理的原因：
 * 1. Lua 脚本的 KEYS 与 Java 侧拼的 key 必须逐字符一致，散写必然出错；
 * 2. 预热(写)、选课(读写)、压测后清场(删)三处共用同一份命名；
 * 3. redis-cli 排查时按前缀 SCAN 一目了然。
 */
public final class CacheKeys {

    private CacheKeys() {}

    // ---------- 课程维度（Lua 原子扣减操作的对象，Stage 2 预热写入 / Stage 3 消费） ----------

    /** course:cap:{courseId} → STRING 剩余名额。Lua DECR 的对象 */
    public static String courseCap(long courseId) {
        return "course:cap:" + courseId;
    }

    /** course:selected:{courseId} → SET 已选学生。Lua SISMEMBER 判重 + SADD */
    public static String courseSelected(long courseId) {
        return "course:selected:" + courseId;
    }

    /** course:detail:{courseId} → 课程详情缓存（L2），空值也缓存（防穿透，短 TTL） */
    public static String courseDetail(long courseId) {
        return "course:detail:" + courseId;
    }

    /** course:list → 课程列表 L2 全量缓存（L1 是 JVM 内 Caffeine，不占 Redis key） */
    public static String courseList() {
        return "course:list";
    }

    // ---------- 学生维度（方案 A 全原子 Lua 用，Stage 5 对比实验） ----------

    /** student:sched:{studentId} → SET 已占时段，元素形如 "3:5"（周三第 5 节） */
    public static String studentSched(long studentId) {
        return "student:sched:" + studentId;
    }

    /** student:credit:{studentId} → STRING 已选学分总数 */
    public static String studentCredit(long studentId) {
        return "student:credit:" + studentId;
    }

    // ---------- 锁 ----------

    /**
     * lock:select:{studentId} → Redisson 学生锁。
     * ★ 锁粒度 = 学生：一个学生的选课操作本就该串行（他不可能同时上两门课的判定），
     *   锁的数据边界与他自己的已选课程集合一致，不同学生之间零竞争。
     *   反面方案（课程锁）会让抢同一门课的上千人全员串行，QPS 直接崩。
     */
    public static String selectLock(long studentId) {
        return "lock:select:" + studentId;
    }

    /** lock:warmup → 预热任务锁，保证多实例部署时预热只执行一次 */
    public static final String WARMUP_LOCK = "lock:warmup";

    /**
     * warmup:students → 上轮预热写入的学生 id 名册（SET）。
     * ★ 为什么有名册：清旧缓存需要知道"上次预热过哪些学生"。学生数大时不能 KEYS/SCAN
     *   （阻塞单线程 Redis，压测期间是事故源），记名册后下轮精确 DEL——
     *   与蓝图第 9 节"全集可枚举就不用 SCAN"是同一思想。
     */
    public static String warmupStudentRoster() {
        return "warmup:students";
    }

    /** lock:rebuild:course:{courseId} → 热点课程缓存重建互斥锁（防击穿，Stage 2） */
    public static String courseRebuildLock(long courseId) {
        return "lock:rebuild:course:" + courseId;
    }

    // ---------- 限流 / 幂等 ----------

    /**
     * rl:select:{studentId} → 令牌桶 HASH{tokens, ts}。
     * ★ 限流粒度按 studentId 而非 IP：校园网 NAT 出口整栋宿舍楼共用一个 IP，
     *   按 IP 限流会把无辜学生全部误杀。
     */
    public static String selectRateLimit(long studentId) {
        return "rl:select:" + studentId;
    }

    /** sel:done:{studentId}:{courseId} → MQ 消费幂等标记（业务去重键，Stage 3） */
    public static String selectionDone(long studentId, long courseId) {
        return "sel:done:" + studentId + ":" + courseId;
    }
}
