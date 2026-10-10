package com.luohaoyu.course.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.luohaoyu.course.common.BizException;
import com.luohaoyu.course.common.CacheKeys;
import com.luohaoyu.course.common.ErrorCode;
import com.luohaoyu.course.domain.entity.Course;
import com.luohaoyu.course.mapper.CourseMapper;
import com.luohaoyu.course.mapper.SelectionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 预热（Stage 2，蓝图完成标志的主角）：把 DB 里的选课现状灌进 Redis。
 *
 * 预热写四类 key（全部 StringRedisTemplate——Lua 要碰的 key 不能带 JSON 引号，
 * 边界约定见 RedisConfig 类注释）：
 * 1. course:cap:{id}      剩余名额 = capacity - selection 表实际在读数
 * 2. course:selected:{id} SET 已选学生 —— Stage 3 Lua SISMEMBER 判重的数据源
 * 3. student:sched:{id}   SET 已占时段 "day:period"（CourseSchedule.toSlot 离散化）
 *    student:credit:{id}  已选学分
 * 4. course:detail:{id} / course:list —— 读路径 L2 直接热起来（这是 L2，
 *    L1 进程内缓存跨实例灌不进，冷启动 60s 自然补齐，见 CourseCacheService 注释）
 *
 * ★ 三个关键取舍（面试会问）：
 * 1. 【为什么用 Redisson 锁】部署是多实例（Stage 6 docker-compose 起 2 个应用），
 *    开抢时刻若各实例都跑一遍预热 = 对同一批 key 写 N 遍，纯浪费还互相踩。
 *    tryLock(0, 5min)：waitTime=0 抢不到立即返回"别的实例在跑"（预热不排他等待，
 *    跳过即可）；leaseTime=5min 是给【实例中途崩溃】兜底——不用看门狗，
 *    因为没有"业务没跑完不能放锁"的诉求，反而是"死了必须自动放锁"。
 * 2. 【清旧 key 为什么有名册】上一轮预热写过的 student:sched，这一轮可能对应
 *    零选课学生（gen-data 清空后），不删就是脏数据。候选方案 KEYS/SCAN：
 *    KEYS 阻塞单线程 Redis（压测期间=事故）；SCAN 要遍历整个 keyspace 才捞得完。
 *    上轮把学生 id 记进 warmup:students，本轮精确 DEL——O(上轮学生数) 而不是 O(Redis 全库)。
 * 3. 【★ 预热只能在开抢前跑】cap 的数据源是 DB，而 Stage 3 起 Redis cap 才是权威、
 *    DB selected_count 靠 MQ 异步追。开抢后重跑 warmup = 用滞后的 DB 覆盖实时的
 *    Redis，被选走的名额会"回涨"——制造假超卖。Day 4 起压测 SOP 里 warmup
 *    固定排在 gen-data 之后、压测开始之前，就是这个原因（写进 NOTES 的压测纪律）。
 *
 * 数据规模：5000 学生 × 50 课，全量 <5 万条 SET/STRING 写，单次预热预期 <3s。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WarmupService {

    private final CourseMapper courseMapper;
    private final SelectionMapper selectionMapper;
    private final StringRedisTemplate stringRedisTemplate;   // Lua 兼容层：纯 string 写入
    private final RedisTemplate<String, Object> redisTemplate; // 对象缓存层：JSON 序列化
    private final RedissonClient redissonClient;
    private final CourseCacheService courseCacheService;

    public Map<String, Object> warmup() {
        RLock lock = redissonClient.getLock(CacheKeys.WARMUP_LOCK);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 5, TimeUnit.MINUTES);
            if (!acquired) {
                Map<String, Object> skipped = new LinkedHashMap<>();
                skipped.put("executed", false);
                skipped.put("reason", "another instance holds lock:warmup");
                return skipped;
            }
            return doWarmup();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private Map<String, Object> doWarmup() {
        long start = System.currentTimeMillis();

        // ---------- 取数：三条 SQL 拿全现状 ----------
        List<Course> courses = courseMapper.selectList(
                new QueryWrapper<Course>().eq("status", 1).orderByAsc("id"));
        List<Map<String, Object>> selRows = selectionMapper.selectActiveSelections();
        List<Map<String, Object>> schedRows = selectionMapper.selectSelectionScheduleRows();

        // course → 已选学生（cap 扣减与 SET 判重共用一份分组结果，避免两遍扫描）
        Map<Long, List<String>> studentsByCourse = new HashMap<>();
        Map<Long, Course> courseById = new HashMap<>();
        Map<Long, Integer> creditByStudent = new HashMap<>();
        for (Course c : courses) {
            courseById.put(c.getId(), c);
        }
        for (Map<String, Object> r : selRows) {
            Long courseId = ((Number) r.get("courseId")).longValue();
            Long studentId = ((Number) r.get("studentId")).longValue();
            studentsByCourse.computeIfAbsent(courseId, k -> new ArrayList<>())
                    .add(String.valueOf(studentId));
            Course c = courseById.get(courseId);
            if (c != null) {
                creditByStudent.merge(studentId, c.getCredit(), Integer::sum);
            }
        }
        // student → 已占时段集合（toSlot 离散点位，与方案 A Lua 的 SISMEMBER 语义一致）
        Map<Long, Set<String>> schedByStudent = new HashMap<>();
        for (Map<String, Object> r : schedRows) {
            Long studentId = ((Number) r.get("studentId")).longValue();
            int day = ((Number) r.get("dayOfWeek")).intValue();
            int from = ((Number) r.get("startPeriod")).intValue();
            int to = ((Number) r.get("endPeriod")).intValue();
            Set<String> slots = schedByStudent.computeIfAbsent(studentId, k -> new HashSet<>());
            for (int p = from; p <= to; p++) {
                slots.add(day + ":" + p);
            }
        }

        // ---------- 清旧：上一轮名册精确删除（见类注释取舍 2） ----------
        Set<String> oldRoster = stringRedisTemplate.opsForSet()
                .members(CacheKeys.warmupStudentRoster());
        if (oldRoster != null && !oldRoster.isEmpty()) {
            List<String> staleKeys = new ArrayList<>();
            for (String sid : oldRoster) {
                staleKeys.add(CacheKeys.studentSched(Long.parseLong(sid)));
                staleKeys.add(CacheKeys.studentCredit(Long.parseLong(sid)));
            }
            stringRedisTemplate.delete(staleKeys);
            stringRedisTemplate.delete(CacheKeys.warmupStudentRoster());
        }

        // ---------- 先废 L1：此刻起读路径要么命中即将写入的新 L2、要么回源 DB，不会读到上一轮对象 ----------
        courseCacheService.evictCourseCaches();

        // ---------- 写课程维度 ----------
        for (Course c : courses) {
            List<String> chosen = studentsByCourse.getOrDefault(c.getId(), List.of());
            int remain = Math.max(0, c.getCapacity() - chosen.size());

            stringRedisTemplate.opsForValue().set(
                    CacheKeys.courseCap(c.getId()), String.valueOf(remain));

            String selectedKey = CacheKeys.courseSelected(c.getId());
            stringRedisTemplate.delete(selectedKey);          // DEL 再 SADD：幂等重跑不残留
            if (!chosen.isEmpty()) {
                stringRedisTemplate.opsForSet().add(selectedKey, chosen.toArray(new String[0]));
            }

            // 展示口径对齐 CourseController.selectedCount 的来源（真实在读数，非滞后列）
            c.setSelectedCount(chosen.size());
            redisTemplate.opsForValue().set(CacheKeys.courseDetail(c.getId()), c,
                    courseCacheService.jitteredTtl(), TimeUnit.SECONDS);
        }
        redisTemplate.opsForValue().set(CacheKeys.courseList(), courses,
                courseCacheService.jitteredTtl(), TimeUnit.SECONDS);

        // ---------- 写学生维度 ----------
        Set<String> roster = new HashSet<>();
        for (Map.Entry<Long, Set<String>> e : schedByStudent.entrySet()) {
            String key = CacheKeys.studentSched(e.getKey());
            stringRedisTemplate.delete(key);
            if (!e.getValue().isEmpty()) {
                stringRedisTemplate.opsForSet().add(key, e.getValue().toArray(new String[0]));
            }
            roster.add(String.valueOf(e.getKey()));
        }
        for (Map.Entry<Long, Integer> e : creditByStudent.entrySet()) {
            stringRedisTemplate.opsForValue().set(
                    CacheKeys.studentCredit(e.getKey()), String.valueOf(e.getValue()));
        }
        if (!roster.isEmpty()) {
            stringRedisTemplate.opsForSet().add(CacheKeys.warmupStudentRoster(),
                    roster.toArray(new String[0]));
        }

        long cost = System.currentTimeMillis() - start;
        log.info("warmup|done|courses={}|studentsWithSel={}|costMs={}",
                courses.size(), schedByStudent.size(), cost);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("executed", true);
        summary.put("courses", courses.size());
        summary.put("studentsWarmed", schedByStudent.size());
        summary.put("totalCapWritten", courses.stream()
                .mapToInt(c -> Math.max(0, c.getCapacity()
                        - studentsByCourse.getOrDefault(c.getId(), List.of()).size()))
                .sum());
        summary.put("costMs", cost);
        return summary;
    }
}
