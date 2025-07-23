package cn.edu.nju.hello.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.Refill;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    // Redis配置（可选，如果不存在会降级到内存限流）
    @Value("${spring.data.redis.host:}")
    private String redisHost;

    @Value("${spring.data.redis.port:0}")
    private int redisPort;

    // Redis限流相关
    private LettuceBasedProxyManager proxyManager;
    private volatile boolean redisAvailable = true;
    private volatile boolean redisChecked = false;

    // 内存限流相关
    private final AtomicLong requestCount = new AtomicLong(0);
    private volatile long lastResetTime = System.currentTimeMillis();
    private static final int RATE_LIMIT = 100; // 每秒100次

    /**
     * 获取Redis代理管理器，如果Redis不可用则返回null
     */
    private LettuceBasedProxyManager getProxyManager() {
        if (!redisChecked) {
            checkRedisAvailability();
        }

        if (!redisAvailable) {
            return null;
        }

        if (proxyManager == null) {
            try {
                RedisClient redisClient = RedisClient.create(
                        RedisURI.builder()
                                .withHost(redisHost)
                                .withPort(redisPort)
                                .withTimeout(Duration.ofSeconds(2))
                                .build()
                );

                proxyManager = LettuceBasedProxyManager.builderFor(redisClient)
                        .withExpirationStrategy(
                                ExpirationAfterWriteStrategy
                                        .basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(1))
                        )
                        .build();
            } catch (Exception e) {
                redisAvailable = false;
                return null;
            }
        }
        return proxyManager;
    }

    /**
     * 检查Redis可用性
     */
    private void checkRedisAvailability() {
        redisChecked = true;

        // 如果配置为空，直接使用内存限流
        if (redisHost == null || redisHost.trim().isEmpty() || redisPort <= 0) {
            redisAvailable = false;
            return;
        }

        try {
            // 尝试创建Redis连接进行测试
            RedisClient testClient = RedisClient.create(
                    RedisURI.builder()
                            .withHost(redisHost)
                            .withPort(redisPort)
                            .withTimeout(Duration.ofSeconds(1))
                            .build()
            );

            // 简单测试连接
            testClient.connect().sync().ping();
            testClient.shutdown();

            redisAvailable = true;
        } catch (Exception e) {
            redisAvailable = false;
        }
    }

    /**
     * Redis限流
     */
    private boolean redisRateLimit(HttpServletRequest request, HttpServletResponse response) throws Exception {
        try {
            // 桶配置:每秒100个令牌,容量100
            BucketConfiguration bucketConfiguration = BucketConfiguration.builder()
                    .addLimit(Bandwidth.classic(100, Refill.intervally(100, Duration.ofSeconds(1))))
                    .build();

            // 全局限流:每秒100次
            Bucket bucket = getProxyManager().builder().build("global".getBytes(), bucketConfiguration);

            if (bucket.tryConsume(1)) {
                return true;
            } else {
                // 返回429
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"Too Many Requests\",\"message\":\"请求频率过高,请稍后重试(Redis)\"}");
                return false;
            }
        } catch (Exception e) {
            // Redis出错时，标记为不可用并降级
            redisAvailable = false;
            return memoryRateLimit(request, response);
        }
    }

    /**
     * 内存限流
     */
    private boolean memoryRateLimit(HttpServletRequest request, HttpServletResponse response) throws Exception {
        long currentTime = System.currentTimeMillis();

        // 每秒重置计数器
        if (currentTime - lastResetTime >= 1000) {
            synchronized (this) {
                if (currentTime - lastResetTime >= 1000) {
                    requestCount.set(0);
                    lastResetTime = currentTime;
                }
            }
        }

        // 检查是否超过限制
        if (requestCount.incrementAndGet() > RATE_LIMIT) {
            // 返回429
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Too Many Requests\",\"message\":\"请求频率过高,请稍后重试(Memory)\"}");
            return false;
        }

        return true;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 跳过actuator端点
        if (request.getRequestURI().startsWith("/actuator/")) {
            return true;
        }

        try {
            // 根据Redis可用性选择限流策略
            if (redisAvailable && getProxyManager() != null) {
                return redisRateLimit(request, response);
            } else {
                return memoryRateLimit(request, response);
            }
        } catch (Exception e) {
            // 如果所有限流都出错，允许请求通过（最终降级策略）
            return true;
        }
    }
}