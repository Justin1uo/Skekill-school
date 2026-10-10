package com.luohaoyu.course.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * RedisTemplate 序列化配置（L2 对象缓存专用：course:detail / course:list）。
 *
 * 为什么不用默认的 JdkSerializationRedisSerializer：
 * 1. redis-cli 里看到的是乱码，Stage 2/3 排查缓存数据时没法肉眼核对；
 * 2. JDK 序列化体积大、且要求对象实现 Serializable，压测下网络开销明显。
 *
 * ★ Stage 2 重写点一：必须手动给 ObjectMapper 注册 JavaTimeModule。
 *   GenericJackson2JsonRedisSerializer 的无参构造内部 new 了一个"裸" ObjectMapper，
 *   不认 JSR-310 时间类型——Course.createdAt 是 LocalDateTime，首次缓存就会抛
 *   "Java 8 date/time type not supported by default"。Spring Boot 自动配置的那
 *   个全局 ObjectMapper（带模块注册）救不了这里，因为序列化器用的是自己的实例。
 *
 * ★ Stage 2 重写点二：activateDefaultTyping 存类型信息。
 *   value 反序列化目标是 Object，没有 @class 字段只能还原成 LinkedHashMap。
 *   白名单（PolymorphicTypeValidator）限制只允许本项目/java 标准包，
 *   防止将来有人往缓存 value 里塞 gadget 做多态反序列化攻击——这是
 *   GenericJackson2Json 默认的 Unsafe 全放开模式的已知风险点。
 *   副作用：redis-cli 里 JSON 会带 "@class" 字段，那是类型信息，不是脏数据。
 *
 * ★ 边界约定：本模板只用于"对象缓存"key（course:detail:*、course:list）。
 *   所有 Lua 要碰的 key（course:cap、course:selected、student:sched 等）一律走
 *   StringRedisTemplate——JSON 序列化器会把 String 写成带引号的 "\"a\""，
 *   Lua 侧 SISMEMBER/GET 的字节级比对会直接失败。混用是这条链路最容易踩的坑。
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(buildObjectMapper());

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);

        template.afterPropertiesSet();
        return template;
    }

    private ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("com.luohaoyu.course.")
                        .allowIfSubType("java.util.")
                        .allowIfSubType("java.lang.")
                        .allowIfSubType("java.time.")
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
        return mapper;
    }
}
