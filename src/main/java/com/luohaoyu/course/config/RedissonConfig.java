package com.luohaoyu.course.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端（单机模式，与本机/服务器单实例 Redis 对应）。
 *
 * 面试相关的两个点：
 * 1. 看门狗：调用 lock.tryLock(waitTime, TimeUnit) 且【不传 leaseTime】时，
 *    Redisson 默认 30s 租期 + 后台看门狗每 10s 续期，业务没执行完锁不会提前失效。
 *    一旦显式传了 leaseTime，看门狗不生效，到点强制释放——所以业务代码里
 *    何时传/不传 leaseTime 是有讲究的（蓝图 7.4 的示例传了 10s，是为了
 *    压测下防止死锁堆积，属于"快速失败优先"的取舍，Stage 3 会细讲）。
 * 2. 单机 Redis 下锁不是 100% 可靠（主从切换可能丢锁），
 *    所以 selection 表的唯一索引是最后防线，两者是配套设计。
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setPassword(password.isEmpty() ? null : password)
                // 锁操作都是短平快（冲突检测 + 一次事务提交），池不用太大；
                // 压测时若出现 Redis 连接等待，先看这里和 Lettuce 池的总量。
                .setConnectionPoolSize(32)
                .setConnectionMinimumIdleSize(8)
                .setConnectTimeout(3000)
                .setTimeout(3000);
        return Redisson.create(config);
    }
}
