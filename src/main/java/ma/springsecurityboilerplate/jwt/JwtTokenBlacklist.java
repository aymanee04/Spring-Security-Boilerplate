package ma.springsecurityboilerplate.jwt;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

// Invalidated JTIs are stored with a TTL equal to the token's remaining lifetime.
// Once the token would have naturally expired, the Redis key self-deletes — no cleanup needed.
// The key prefix "blacklist:" isolates these entries from other Redis namespaces.
@Component
@RequiredArgsConstructor
public class JwtTokenBlacklist {

    private static final String PREFIX = "blacklist:";

    private final StringRedisTemplate redisTemplate;

    public void blacklist(String jti, long ttlMs) {
        redisTemplate.opsForValue().set(PREFIX + jti, "1", Duration.ofMillis(ttlMs));
    }

    public boolean isBlacklisted(String jti) {
        return redisTemplate.hasKey(PREFIX + jti);
    }
}
