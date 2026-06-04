package ma.springsecurityboilerplate.jwt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// Binds to docker-compose.yml under application.jwt.*
// Using @ConfigurationProperties over @Value keeps all JWT settings in one typed object,
// making it easy to inject into any class that needs token parameters.
@Configuration
@ConfigurationProperties(prefix = "application.jwt")
@Getter
@Setter
public class JwtConfig {
    private String secret;
    private long accessTokenExpiry;
    private long refreshTokenExpiry;
}
