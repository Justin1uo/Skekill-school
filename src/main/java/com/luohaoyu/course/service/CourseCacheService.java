package com.luohaoyu.course.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.luohaoyu.course.common.CacheKeys;
import com.luohaoyu.course.common.ErrorCode;
import com.luohaoyu.course.domain.entity.Course;
import com.luohaoyu.course.mapper.CourseMapper;
import com.luohaoyu.course.mapper.SelectionMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 课程读路径多级缓存（Stage 2）：Caffeine L1（进程内）+ Redis L2。
 *
 * 蓝图四件套在这里的对应关系（面试自查）：
 * - 【雪崩】L2 TTL = 基础值 + 随机抖动：不加抖动的画像是"开场瞬间集体冷"——50 门课
 *   同时写入、同时过期，DB 在同一毫秒收到全量回源。抖动把过期摊到 60s 窗口。
 *   ★ 注意这与 DataGen 固定 SEED 不冲突：SEED 管的是【数据分布】要可复现，
 *   TTL 抖动管的是【基础设施时间】，抖动本来就该每次不同，压测复现不受影响。
 * - 【穿透】查不存在的 courseId：DB 查不到也往 L2 写 "__NULL__" 短标记（60s），
 *   同一个不存在的 id 再问直接命中缓存——恶意刷 /api/courses/999999 打不穿第二层。
 *   不用布隆过滤器（蓝图第 9 节裁决：全集可枚举，空值缓存足够）。
 * - 【击穿】热点 course:detail 过期瞬间，N 线程同时回源：Redisson
 *   lock:rebuild:course:{id} 互斥重建；抢不到锁的快速失败（见 rebuildDetail 内注释）。
 * - 【预热】WarmupService 启动/手动灌 L2（L1 是进程内的，跨实例预热灌不到，
 *   冷启动靠 60s 短 TTL 自然补齐——这就是"预热只预热门 L2"的边界）。
 *
 * ★ 一致性边界（必须能讲）：本缓存【只挂读路径】。写路径（gen-data / warmup）
 *   显式调用 evictCourseCaches() 清 L1+L2；Stage 3 的 Lua 扣减不更新这里——
 *   course:cap 是权威余量，detail/list 里的 selectedCount 只是展示用的近似值，
 *   最大滞后 = L1 60s。简历口径"课程查询走缓存"指的是详情/列表，不是余量判定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseCacheService {

    /** 空值缓存标记。用 String 常量而不是 null：Redis 存不了"不存在的 key"和"值为 null"的区别 */
    private static final String NULL_MARKER = "__NULL__";

    /** L1：课程详情。Optional 包一层——Caffeine 不允许缓存 null，"确认不存在"也要占位 */
    private final Cache<Long, Optional<Course>> detailL1 = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(500)
            .build();

    /** L1：课程列表。只有一个 key，maximumSize=1 是刻意的（防止将来误加参数变成无界缓存） */
    private final Cache<String, List<Course>> listL1 = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(1)
            .build();

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;
    private final CourseMapper courseMapper;
    private final SelectionMapper selectionMapper;

    @Value("${selection.cache.l2-base-seconds:300}")
    private long l2BaseSeconds;

    @Value("${selection.cache.l2-jitter-seconds:60}")
    private long l2JitterSeconds;

    @PostConstruct
    public void logConfig() {
        log.info("cache ready: L1=60s(Caffeine), L2={}s±{}s(Redis)", l2BaseSeconds, l2JitterSeconds);
    }

    /**
     * 雪崩抖动的唯一来源：TTL = base + U(0, jitter]。每次写入独立随机。
     * ★ public：WarmupService 灌 L2 必须走同一个 TTL 口径，
     *   两处各写一份参数就会漂移（"缓存 300s"面试讲 300、代码查出两处不一样）。
     */
    public long jitteredTtl() {
        return l2BaseSeconds + ThreadLocalRandom.current().nextLong(1, l2JitterSeconds + 1);
    }

    // ==================== 课程详情 ====================

    /**
     * 三层取数：L1 → L2 → DB（DB 兜底时写回 L2，穿透/击穿的防护都收在 loadFromL2OrDb）。
     * 返回 null 表示课程不存在（调用方决定报 1001）。
     */
    public Course getDetail(long courseId) {
        // Caffeine.get(key, mappingFunction) 对同一 key 的并发 loader 只跑一个——
        // 进程内这半边天然互斥，跨进程的互斥靠下面的 Redisson 锁
        Optional<Course> hit = detailL1.get(courseId, this::loadFromL2OrDb);
        return hit == null ? null : hit.orElse(null);
    }

    private Optional<Course> loadFromL2OrDb(long courseId) {
        String key = CacheKeys.courseDetail(courseId);

        // ---- L2 ----
        Object cached = redisTemplate.opsForValue().get(key);
        if (NULL_MARKER.equals(cached)) {
            log.debug("cache|layer=L2|type=NULL|courseId={}", courseId);
            return Optional.empty();
        }
        if (cached instanceof Course c) {
            return Optional.of(c);
        }

        // ---- L2 miss：互斥重建 ----
        return rebuildDetail(courseId, key);
    }

    private Optional<Course> rebuildDetail(long courseId, String key) {
        RLock lock = redissonClient.getLock(CacheKeys.courseRebuildLock(courseId));
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 10, TimeUnit.SECONDS);
            if (!acquired) {
                // 已有别的线程/实例在重建。这里选【快速失败】而不是排队等锁：
                // 等锁会把重建者的耗时转嫁给所有竞争者（并发越高放大越狠），
                // 而重建只需毫秒级——睡 50ms 再读一次 L2，大概率已命中；
                // 仍没有就直接读 DB 返回、但不写回（写回是持有者的职责）。
                Thread.sleep(50);
                Object again = redisTemplate.opsForValue().get(key);
                if (again instanceof Course c) {
                    return Optional.of(c);
                }
                if (NULL_MARKER.equals(again)) {
                    return Optional.empty();
                }
                log.debug("cache|layer=DB|bypass=lock-lost|courseId={}", courseId);
                return Optional.ofNullable(courseMapper.selectById(courseId));
            }

            // double check：拿到锁的瞬间，前一位持有者可能刚写好（拿锁前它正在重建）
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached instanceof Course c) {
                return Optional.of(c);
            }
            if (NULL_MARKER.equals(cached)) {
                return Optional.empty();
            }

            // ---- DB 兜底 + 写回 ----
            Course course = courseMapper.selectById(courseId);
            if (course == null) {
                // ★ 穿透防线：查不到也要写"不存在"，但 TTL 用独立的短值 60s——
                //   太短防不住持续攻击，太长课程上线后要等它过期才可见
                redisTemplate.opsForValue().set(key, NULL_MARKER, 60, TimeUnit.SECONDS);
                log.warn("cache|type=MISS-DB|write=NULL_MARKER|courseId={}", courseId);
                return Optional.empty();
            }
            long ttl = jitteredTtl();
            redisTemplate.opsForValue().set(key, course, ttl, TimeUnit.SECONDS);
            log.debug("cache|type=REBUILD|courseId={}|ttl={}", courseId, ttl);
            return Optional.of(course);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.ofNullable(courseMapper.selectById(courseId));
        } finally {
            // isHeldByCurrentThread：只能释放自己持有的锁，防解锁别人的锁（Redisson 常见事故）
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // ==================== 课程列表 ====================

    /**
     * 列表整体当【一个缓存单元】（50 门课一次查、一次存）。
     * ★ 这里刻意不加 Redisson 锁：列表击穿的最坏代价是 N 线程各跑一条恒定两次的
     *   SELECT（全表 status=1 + 一次 GROUP BY），量级固定且小；锁的 RTT 成本反而
     *   高于收益。击穿防护的力度要跟"击穿后 DB 的受伤程度"匹配——点查热点才值得互斥。
     */
    public List<Course> list() {
        return listL1.get("ALL", k -> {
            Object cached = redisTemplate.opsForValue().get(CacheKeys.courseList());
            if (cached instanceof List<?> raw) {
                @SuppressWarnings("unchecked")
                List<Course> list = (List<Course>) raw;
                return list;
            }
            List<Course> fromDb = loadListFromDb();
            redisTemplate.opsForValue().set(CacheKeys.courseList(), fromDb, jitteredTtl(), TimeUnit.SECONDS);
            return fromDb;
        });
    }

    /**
     * 取数逻辑继承 Stage 1 的 /api/courses 实现：固定两条 SQL（课程全量 + 一次 GROUP BY
     * 统计真实在读数），无 N+1。selectedCount 是展示近似值，权威余量看 course:cap。
     */
    private List<Course> loadListFromDb() {
        List<Course> courses = courseMapper.selectList(
                new QueryWrapper<Course>().eq("status", 1).orderByAsc("id"));
        Map<Long, Integer> counts = new HashMap<>();
        for (Map<String, Object> row : selectionMapper.countSelectedGroupByCourse()) {
            Long courseId = ((Number) row.get("courseId")).longValue();
            counts.put(courseId, ((Number) row.get("cnt")).intValue());
        }
        courses.forEach(c -> c.setSelectedCount(counts.getOrDefault(c.getId(), 0)));
        return courses;
    }

    // ==================== 一致性维护 ====================

    /**
     * 写路径（gen-data / warmup）后的显式失效：清 L1 + 删该数据集合对应的 L2 key。
     * L2 detail 的 key 全集 = 当前 course 表的 id（可枚举，所以精确 DEL，不 SCAN 模式）。
     */
    public void evictCourseCaches() {
        detailL1.invalidateAll();
        listL1.invalidateAll();
        redisTemplate.delete(CacheKeys.courseList());
        List<Course> all = courseMapper.selectList(new QueryWrapper<Course>().select("id"));
        all.stream()
                .map(c -> CacheKeys.courseDetail(c.getId()))
                .forEach(k -> redisTemplate.delete(k));
        log.info("cache|evict|details={}", all.size());
    }
}
