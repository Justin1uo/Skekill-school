package com.luohaoyu.course.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.luohaoyu.course.common.BizException;
import com.luohaoyu.course.common.ErrorCode;
import com.luohaoyu.course.domain.entity.Course;
import com.luohaoyu.course.domain.entity.Selection;
import com.luohaoyu.course.mapper.CourseMapper;
import com.luohaoyu.course.mapper.SelectionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ★★ v1 朴素选课 —— Stage 5 压测基线，禁止提前优化 ★★
 *
 * 直接查库判余量 + 查重 + INSERT，无锁 / 无缓存 / 无 Lua / 无 MQ。
 * 这不是"没写完的代码"，而是刻意保留的对照组：v2/v3/v4 的每一分提升都要以它为原点。
 *
 * 为什么这版在并发下必然超卖（面试必考，看懂这个时间线）：
 *   容量 100、已选 99 时，两个请求并发进来——
 *   T1: SELECT COUNT(*) → 99 (<100，放行)
 *   T2:                SELECT COUNT(*) → 99 (<100，也放行)
 *   T1: INSERT 成功            → 已选 100
 *   T2:                       INSERT 成功 → 已选 101 ← 超卖发生
 *   "查余量"和"写入"之间存在竞态窗口（check-then-act），
 *   任何在应用层做的判断都挡不住另一个线程在你判断之后、写入之前的插入。
 *
 * 重复选课同理（两次 exists 检查都看到"没选过"→ 双写），
 * 但那一处会被 uk_student_course 唯一索引兜住（第二条 INSERT 直接报错 → 1003）；
 * ★ 而超卖唯一索引救不了——两条 selection 是【不同 student_id】，不违反任何约束。
 * 这就是"唯一索引和 Redis 谁先兜底、各兜什么"的答案（蓝图追问 14）。
 *
 * 本版本只保证"单线程语义正确"：串行调用下 1001/1002/1003 判定无误。
 * 时间冲突 / 学分上限校验属于 Stage 3（锁 + 事务层里做），此处不做。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SelectionService {

    private final CourseMapper courseMapper;
    private final SelectionMapper selectionMapper;

    public Long select(Long studentId, Long courseId) {
        // 1. 课程存在性（查 DB，v1 没有缓存层）
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            log.warn("select|result=REJECT|code=1001|studentId={}|courseId={}", studentId, courseId);
            throw new BizException(ErrorCode.COURSE_NOT_FOUND);
        }

        // 2. 判余量 —— 竞态窗口的前半段（读），Stage 3 交给 Lua 的 GET+DECR 原子化
        Long chosen = selectionMapper.selectCount(new QueryWrapper<Selection>()
                .eq("course_id", courseId)
                .eq("status", 1));
        if (chosen >= course.getCapacity()) {
            log.warn("select|result=REJECT|code=1002|studentId={}|courseId={}|chosen={}|cap={}",
                    studentId, courseId, chosen, course.getCapacity());
            throw new BizException(ErrorCode.COURSE_FULL);
        }

        // 3. 判重复 —— 竞态窗口的另一半，但写入端有唯一索引兜底
        Long mine = selectionMapper.selectCount(new QueryWrapper<Selection>()
                .eq("student_id", studentId)
                .eq("course_id", courseId)
                .eq("status", 1));
        if (mine > 0) {
            log.warn("select|result=REJECT|code=1003|studentId={}|courseId={}", studentId, courseId);
            throw new BizException(ErrorCode.DUPLICATE_SELECTION);
        }

        // 4. 写入 —— status=1 已选；若将来支持退选(status=0 再重选)，
        //    Stage 3 里走 UPDATE 复用该行，不再 INSERT（否则撞 uk_student_course）
        Selection selection = new Selection();
        selection.setStudentId(studentId);
        selection.setCourseId(courseId);
        selection.setStatus(1);
        selectionMapper.insert(selection);   // id 由雪花算法生成

        log.info("select|result=OK|studentId={}|courseId={}|selectionId={}", studentId, courseId, selection.getId());
        return selection.getId();
    }
}
