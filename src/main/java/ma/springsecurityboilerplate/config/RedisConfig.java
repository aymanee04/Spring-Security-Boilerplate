package ma.springsecurityboilerplate.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

// StringRedisTemplate is registered as the primary template for simple key/value string operations
// (blacklist, refresh tokens). A generic RedisTemplate<String, Object> is also provided for
// Bucket4j's distributed state, which requires byte-level access.
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    public RedisTemplate<String, byte[]> byteRedisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new org.springframework.data.redis.serializer.RedisSerializer<byte[]>() {
            @Override
            public byte[] serialize(byte[] value) { return value; }
            @Override
            public byte[] deserialize(byte[] bytes) { return bytes; }
            @Override
            public Class<byte[]> getTargetType() { return byte[].class; }
        });
        return template;
    }
}
