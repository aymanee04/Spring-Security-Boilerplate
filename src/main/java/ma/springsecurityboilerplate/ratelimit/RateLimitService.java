package ma.springsecurityboilerplate.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

// Each IP gets its own Bucket stored in Redis via Lettuce. The ProxyManager lazily creates buckets
// on first request and reuses them on subsequent calls — no in-memory state on the app server.
// This makes the rate limiter horizontally scalable across multiple instances.
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final ProxyManager<String> proxyManager;
    private final Supplier<BucketConfiguration> configSupplier;

    public RateLimitService(
            @org.springframework.beans.factory.annotation.Value("${spring.data.redis.host:localhost}") String redisHost,
            @org.springframework.beans.factory.annotation.Value("${spring.data.redis.port:6379}") int redisPort,
            RateLimitConfig rateLimitConfig) {
        // Build a RedisURI from configured host/port so we get a structured URI instead of a plain string.
        RedisURI redisUri = RedisURI.builder().withHost(redisHost).withPort(redisPort).build();

        ProxyManager<String> tmpProxyManager = null;
        try {
            @SuppressWarnings("resource")
            RedisClient redisClient = RedisClient.create(redisUri);
            StatefulRedisConnection<String, byte[]> connection =
                    redisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));

            tmpProxyManager = LettuceBasedProxyManager.builderFor(connection).build();
            log.info("Initialized Redis-backed rate limiter using Redis URI: {}:{}", redisHost, redisPort);
        } catch (Exception e) {
            // Don't break application startup - Bucket4j will fail-open, but surface the error in logs.
            log.error("Failed to initialize Redis-based rate limiter (requests will be allowed - fail-open).", e);
        }

        this.proxyManager = tmpProxyManager;

        this.configSupplier = () -> BucketConfiguration.builder()
                .addLimit(rateLimitConfig.buildBandwidth())
                .build();
    }

    public boolean tryConsume(String ip) {
        if (this.proxyManager == null) {
            // Redis unavailable during initialization - allow the request (fail-open)
            log.debug("ProxyManager not initialized, allowing request for IP {} (fail-open).", ip);
            return true;
        }

        try {
            Bucket bucket = proxyManager.builder().build("rate-limit:" + ip, configSupplier);
            return bucket.tryConsume(1);
        } catch (Exception e) {
            // In case of runtime Redis errors, fail-open but log for visibility.
            log.error("Runtime error while trying to consume rate limit token for IP {} - allowing request (fail-open).", ip, e);
            return true;
        }
    }
}
