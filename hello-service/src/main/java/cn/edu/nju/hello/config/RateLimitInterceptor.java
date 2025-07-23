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

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    private LettuceBasedProxyManager proxyManager;

    private LettuceBasedProxyManager getProxyManager() {
        if (proxyManager == null) {
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
        }
        return proxyManager;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 跳过actuator端点
        if (request.getRequestURI().startsWith("/actuator/")) {
            return true;
        }

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
                response.getWriter().write("{\"error\":\"Too Many Requests\",\"message\":\"请求频率过高,请稍后重试\"}");
                return false;
            }
        } catch (Exception e) {
            // 如果限流出错，允许请求通过（降级策略）
            System.err.println("限流检查失败: " + e.getMessage());
            return true;
        }
    }
}