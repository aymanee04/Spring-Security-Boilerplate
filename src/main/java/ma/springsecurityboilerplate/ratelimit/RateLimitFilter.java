package ma.springsecurityboilerplate.ratelimit;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;

// @Order(1) places this before the Spring Security filter chain (which defaults to order 100-ish).
// Using a raw javax/jakarta Filter — not OncePerRequestFilter — because we want it fired at the
// servlet container level, before Spring creates a SecurityContext, minimising overhead for
// rejected requests.
@Component
@Order(1)
@RequiredArgsConstructor
public class RateLimitFilter implements Filter {

    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpRes = (HttpServletResponse) response;

        String ip = resolveClientIp(httpReq);

        if (!rateLimitService.tryConsume(ip)) {
            httpRes.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            httpRes.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(httpRes.getWriter(),
                    Map.of("error", "Too many requests", "status", 429));
            return;
        }

        chain.doFilter(request, response);
    }

    // Respect X-Forwarded-For when behind a reverse proxy (NGINX, load balancer).
    // Take the first IP in the chain — that is the original client.
    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
