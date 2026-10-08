package com.luohaoyu.course.service;

import com.luohaoyu.course.mapper.AdminMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 压测数据生成（Stage 1）。5000 学生 × 50 门课 × 每门容量 100。
 *
 * 三个刻意的设计决策（面试可讲）：
 * 1. 【固定随机种子】时段/学分可复现——v1 和 v4 压测用的是同一份数据分布，
 *    对照组之间差异才只来自技术改造，而不是"这次随机出的课更热门"。
 * 2. 【ID 用连续小整数 1..N】而不是业务路径的雪花 ID：
 *    curl 验证、JMeter CSV、redis-cli 抽查都能肉眼写死 courseId=1，
 *    确定性测试数据是压测可信度的地基。
 * 3. 【幂等策略选"先清空再生成"而不是"已存在则跳过"】：
 *    压测要求每轮初始状态完全一致（selected_count=0、selection 表空），
 *    "跳过"会残留上一轮选课记录，v1 的超卖计数（SQL 查 selection 表）直接失真。
 *
 * 实现用 JdbcTemplate 批量插入而非逐条 mapper.insert：
 * 5000 次单条 INSERT 走 5000 个网络往返，配合 JDBC URL 里的
 * rewriteBatchedStatements=true，batchUpdate 被 MySQL 合并成真·多值 INSERT，
 * 整个 gen 在秒级完成（这也是一次对"为什么连接串要加这个参数"的实证）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataGenService {

    private static final int STUDENT_COUNT = 5000;
    private static final int COURSE_COUNT = 50;
    private static final int COURSE_CAPACITY = 100;
    private static final long SEED = 20260926L;

    private static final String[] GRADES = {"2022", "2023", "2024", "2025"};
    private static final String[] MAJORS = {"电子信息工程", "计算机", "自动化", "数学", "英语"};

    /**
     * 课程名/教师名：手工精选的静态清单（各 50 条，按 id 一一对应），不用爬虫——
     * 名单必须内容固定 + 可复现（决策 1），爬虫源不稳定且引入新依赖。
     * 注意：本课表把教师从"rnd 随机抽"改成"按下标直取"后，同一 SEED 下
     * 后续学分/时段的随机序列整体变化——无碍，压测只要求【自 v1-baseline
     * tag 起冻结生成器代码】，所有对照组跑同一份冻结分布。
     */
    private static final String[] COURSE_NAMES = {
            "信号与系统", "模拟电子技术", "数字电路逻辑设计", "电磁场与电磁波", "数字信号处理",
            "通信原理", "微机原理与接口技术", "嵌入式系统设计", "传感器原理与应用", "高频电子线路",
            "数据结构与算法", "计算机组成原理", "操作系统", "计算机网络", "数据库系统原理",
            "编译原理", "Java程序设计", "Python数据分析", "人工智能导论", "机器学习",
            "分布式系统", "软件工程", "计算机图形学", "信息安全概论", "高等数学（上）",
            "高等数学（下）", "线性代数", "概率论与数理统计", "离散数学", "复变函数与积分变换",
            "数学建模", "大学英语（三）", "大学英语（四）", "英语视听说", "英语写作",
            "综合英语", "教育心理学", "现代教育技术", "创新创业基础", "大学物理",
            "物理实验", "电子线路实验", "FPGA设计与应用", "单片机原理及应用", "图像处理与计算机视觉",
            "自然语言处理", "云计算与虚拟化", "大数据技术基础", "物联网通信技术", "数字逻辑设计实验"
    };

    private static final String[] TEACHER_NAMES = {
            "陈志强", "刘雨欣", "王建国", "李慧敏", "张宏伟",
            "赵静", "周立群", "吴晓东", "徐若兰", "孙德彪",
            "胡海涛", "朱明辉", "高雅琴", "林国栋", "何秀英",
            "郭永年", "马文倩", "罗志勇", "梁思远", "宋佳怡",
            "唐鹏程", "许晴岚", "韩立文", "冯玉柱", "邓春霞",
            "曹锦程", "彭飞", "曾宪明", "肖正平", "田佳禾",
            "董雪", "袁斌", "潘越", "蒋大伟", "钟秀荣",
            "戴文远", "John Smith", "Mary Johnson", "James Miller", "Sarah Cooper",
            "David Wilson", "范晓娟", "方蕙", "石亮", "龚霞",
            "白慧", "崔浩", "雷冬梅", "侯敏", "贺东"
    };

    private final AdminMapper adminMapper;
    private final JdbcTemplate jdbcTemplate;

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> regenerate() {
        long start = System.currentTimeMillis();

        adminMapper.clearSelection();
        adminMapper.clearCourseSchedule();
        adminMapper.clearCourse();
        adminMapper.clearStudent();

        Random rnd = new Random(SEED);

        List<Object[]> students = new ArrayList<>(STUDENT_COUNT);
        for (int i = 1; i <= STUDENT_COUNT; i++) {
            students.add(new Object[]{
                    (long) i,
                    String.format("S%05d", i),
                    "学生" + i,
                    GRADES[rnd.nextInt(GRADES.length)],
                    MAJORS[rnd.nextInt(MAJORS.length)],
                    30
            });
        }
        int nStudent = jdbcTemplate.batchUpdate(
                "INSERT INTO student (id, student_no, name, grade, major, max_credit) VALUES (?,?,?,?,?,?)",
                students).length;

        List<Object[]> courses = new ArrayList<>(COURSE_COUNT);
        for (int i = 1; i <= COURSE_COUNT; i++) {
            courses.add(new Object[]{
                    (long) i,
                    String.format("C%03d", i),
                    COURSE_NAMES[i - 1],
                    TEACHER_NAMES[i - 1],
                    1 + rnd.nextInt(4),          // 学分 1-4
                    COURSE_CAPACITY,
                    0,                            // selected_count 归零，与 Redis 预热后的起点一致
                    1                             // status 开放
            });
        }
        int nCourse = jdbcTemplate.batchUpdate(
                "INSERT INTO course (id, course_code, name, teacher, credit, capacity, selected_count, status) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                courses).length;

        // 每门课 1-2 个时段，时段 = 周几 + 起止节次（1-2 节连排），同一课内允许重叠，冲突语义按"点位集合"处理
        List<Object[]> schedules = new ArrayList<>();
        long scheduleId = 1;
        for (int c = 1; c <= COURSE_COUNT; c++) {
            int slotCount = 1 + rnd.nextInt(2);
            for (int s = 0; s < slotCount; s++) {
                int day = 1 + rnd.nextInt(7);
                int startP = 1 + rnd.nextInt(12);
                int endP = Math.min(12, startP + 1 + rnd.nextInt(2));   // 1-2 节
                schedules.add(new Object[]{scheduleId++, (long) c, day, startP, endP});
            }
        }
        int nSchedule = jdbcTemplate.batchUpdate(
                "INSERT INTO course_schedule (id, course_id, day_of_week, start_period, end_period) VALUES (?,?,?,?,?)",
                schedules).length;

        long cost = System.currentTimeMillis() - start;
        log.info("gen-data done: students={}, courses={}, schedules={}, cost={}ms", nStudent, nCourse, nSchedule, cost);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("students", nStudent);
        summary.put("courses", nCourse);
        summary.put("schedules", nSchedule);
        summary.put("capacityPerCourse", COURSE_CAPACITY);
        summary.put("idRange", "student:1-" + STUDENT_COUNT + ", course:1-" + COURSE_COUNT);
        summary.put("costMs", cost);
        return summary;
    }
}
