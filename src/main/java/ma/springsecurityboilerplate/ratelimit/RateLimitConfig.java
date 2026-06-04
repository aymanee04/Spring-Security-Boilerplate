package ma.springsecurityboilerplate.ratelimit;

import io.github.bucket4j.Bandwidth;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

// Bucket policy is kept in a config class so capacity and refill rate can be tuned
// per-environment via docker-compose.yml without touching code.
@Configuration
@ConfigurationProperties(prefix = "application.rate-limit")
@Getter
@Setter
public class RateLimitConfig {

    private int capacity = 20;
    private int refillTokens = 20;
    private int refillDurationSeconds = 60;

    public Bandwidth buildBandwidth() {
        return Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(refillTokens, Duration.ofSeconds(refillDurationSeconds))
                .build();
    }
}
