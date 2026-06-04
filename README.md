# Spring Boot 3 Auth Starter

Production-grade authentication boilerplate: JWT + Redis + distributed rate limiting.  
Java 21 · Spring Boot 3.2 · Spring Security 6 · jjwt 0.12 · Bucket4j · PostgreSQL

---

## Quick Start

```bash
# 1. Start dependencies
docker run -d -p 5432:5432 -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=authdb postgres:16
docker run -d -p 6379:6379 redis:7

# 2. Run
./mvnw spring-boot:run
```

Base URL: `http://localhost:8080/api`

---

## Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/auth/register` | Public | Create account, returns access token |
| POST | `/auth/login` | Public | Login, returns access token |
| POST | `/auth/refresh` | Cookie | Rotate refresh token |
| POST | `/auth/logout` | Bearer | Blacklist access token, clear refresh |

### Register / Login request body
```json
{ "email": "user@example.com", "password": "password123" }
```

### Response body
```json
{ "accessToken": "eyJ...", "tokenType": "Bearer", "expiresIn": 900 }
```
The refresh token is set as an `HttpOnly; Secure; SameSite=Strict` cookie automatically.

---

## Request Flow

```
Incoming request
     │
     ▼
RateLimitFilter          ← Order(1), per-IP bucket in Redis via Bucket4j
     │  429 if exceeded
     ▼
JwtAuthFilter            ← Extracts Bearer token, validates signature, checks blacklist
     │  sets SecurityContext if valid
     ▼
SecurityConfig chain     ← Enforces permitAll / authenticated / hasAuthority rules
     │
     ▼
AuthController           ← Validates input (@Valid), delegates to AuthService
     │
     ▼
AuthService              ← Business logic: register, login, refresh rotation, logout
     │
     ├──▶ PostgreSQL (UserRepository / JPA)
     └──▶ Redis (refresh tokens, JTI blacklist, rate-limit buckets)
```

---

## Token Strategy

| Token | Lifetime | Storage | Delivery |
|-------|----------|---------|----------|
| Access | 15 min | Stateless (no server state) | Response body |
| Refresh | 7 days | Redis (revocable) | HttpOnly cookie |

**Logout** stores the access token's JTI in Redis with TTL = remaining lifetime.  
**Refresh rotation** issues a new refresh token on every use; replayed tokens trigger full session invalidation.

---

## Package Structure

```
com.starter.auth/
├── config/
│   ├── SecurityConfig.java       Filter chain, CORS, BCrypt, AuthManager
│   └── RedisConfig.java          StringRedisTemplate + byte[] template for Bucket4j
├── controller/
│   └── AuthController.java       REST endpoints, cookie management
├── dto/
│   └── AuthDtos.java             AuthRequest, RegisterRequest, AuthResponse (nested)
├── entity/
│   ├── User.java                 JPA entity + UserDetails impl
│   └── Role.java                 ROLE_USER, ROLE_ADMIN
├── jwt/
│   ├── JwtConfig.java            @ConfigurationProperties binding
│   ├── JwtTokenProvider.java     Generate / parse / validate tokens
│   ├── JwtTokenBlacklist.java    Redis-backed JTI store
│   └── JwtAuthFilter.java        OncePerRequestFilter: token → SecurityContext
├── ratelimit/
│   ├── RateLimitConfig.java      Bucket bandwidth policy
│   ├── RateLimitService.java     Bucket4j + Lettuce ProxyManager
│   └── RateLimitFilter.java      Servlet filter Order(1)
├── repository/
│   └── UserRepository.java
├── security/
│   ├── AuthEntryPoint.java       401 JSON handler
│   ├── CustomAccessDeniedHandler.java  403 JSON handler
│   └── GlobalExceptionHandler.java     @RestControllerAdvice
└── service/
    ├── AuthService.java          Core auth logic
    └── UserService.java          UserDetailsService impl
```

---

## Transplanting into a New Project

Copy these three packages wholesale — they have no internal cross-dependencies:

```
jwt/          → self-contained, depends only on jjwt + Redis
ratelimit/    → self-contained, depends only on Bucket4j + Lettuce + Redis
security/     → exception handlers, entry points (no domain deps)
```

Then copy `config/`, `controller/`, `service/`, `dto/`, `entity/`, `repository/` and adjust the base package name in one find-replace.

**Checklist:**
 - [ ] Replace JWT secret with an env var: `${APPLICATION_JWT_SECRET}` in `application.yml`
- [ ] Set `cookie.setSecure(true)` (already on) — confirm HTTPS in prod
- [ ] Update CORS `allowedOrigins` in `SecurityConfig`
- [ ] Tune `application.rate-limit.*` for your traffic profile
- [ ] Add `@EnableConfigurationProperties` on your main class if you extract packages

---

## Production Hardening Checklist

- [ ] JWT secret via env var / secrets manager, minimum 64 chars
- [ ] Redis AUTH password set
- [ ] PostgreSQL credentials via env vars
- [ ] HTTPS only (refresh cookie is `Secure`)
- [ ] CORS locked to your real frontend origin
- [ ] BCrypt strength ≥ 12 (already default)
- [ ] Rate limit tuned for expected traffic
- [ ] Health / actuator endpoints secured or excluded
