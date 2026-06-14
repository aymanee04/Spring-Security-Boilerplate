# Spring Boot 3 Auth Boilerplate

A production-ready authentication and security foundation built for real projects.
Drop it in, configure your `.env`, and start building your features on top of it.

---

## Technologies

### Core
| Technology | Version | Role |
|---|---|---|
| Java | 17 | Language |
| Spring Boot | 3.2.5 | Application framework |
| Spring Security | 6.x | Security filter chain, authentication |
| Spring Data JPA | 3.2.x | Database ORM layer |
| Spring Data Redis | 3.2.x | Redis integration |
| Maven | 3.9.x | Build and dependency management |

### Authentication & Tokens
| Technology | Version | Role |
|---|---|---|
| jjwt | 0.12.5 | JWT generation, signing, parsing |
| BCrypt | Built-in | Password hashing (strength 12) |

### Rate Limiting
| Technology | Version | Role |
|---|---|---|
| Bucket4j | 8.10.1 | Token bucket algorithm implementation |
| Lettuce | Built-in | Redis client used by Bucket4j for distributed buckets |

### Database & Storage
| Technology | Version | Role |
|---|---|---|
| PostgreSQL | 16 | Primary database (users table) |
| Redis | 7 | Refresh tokens, JWT blacklist, rate limit buckets |

### Infrastructure
| Technology | Role |
|---|---|
| Docker | Containerisation |
| Docker Compose | Multi-container orchestration |

---

## How It Works

### The Token Bucket Algorithm (Rate Limiting)
Every IP address gets a virtual bucket holding tokens. Each request consumes one token.
When the bucket is empty the request is blocked with `429 Too Many Requests`.
Tokens refill automatically over time. This prevents burst abuse and DDoS across
**all endpoints** in the project — not just auth routes.

```
Full bucket  → ● ● ● ● ● ● ● ● ● ● (10 tokens)
After 7 reqs → ● ● ●               (3 tokens left)
After 3 more → empty → 429 blocked
After 60s    → ● ● ● ● ● ● ● ● ● ● (refilled)
```

### JWT Strategy
Two tokens are issued on login/register:

| Token | Lifetime | Where it lives | How it travels |
|---|---|---|---|
| Access token | 15 minutes | Stateless — no server storage | Response body → `Authorization: Bearer` header |
| Refresh token | 7 days | Redis (can be revoked) | HttpOnly cookie → auto-sent to `/auth/refresh` only |

**Why two tokens?**
The access token is short-lived so a stolen token expires quickly.
The refresh token lives in an HttpOnly cookie so JavaScript cannot read it,
protecting it from XSS attacks. It lives in Redis so it can be explicitly revoked on logout.

### Logout & Blacklisting
On logout, the access token's unique ID (JTI claim) is stored in Redis with a TTL equal
to its remaining lifetime. Every request checks this blacklist. Once the token would have
naturally expired, Redis auto-deletes the key — no maintenance needed.

### Refresh Token Rotation
Every call to `/auth/refresh` issues a completely new refresh token and invalidates the old one.
If an old refresh token is used again (possible theft scenario), the system detects the reuse,
wipes the entire session from Redis, and forces the user to log in again.

---

## Request Flow

Every incoming request passes through these layers in order:

```
Incoming HTTP Request
        │
        ▼
① RateLimitFilter          @Order(1) — first thing that runs
  └─ reads client IP (respects X-Forwarded-For for proxies)
  └─ checks per-IP token bucket in Redis via Bucket4j
  └─ 429 if bucket empty, otherwise continue
        │
        ▼
② JwtAuthFilter            OncePerRequestFilter
  └─ extracts Bearer token from Authorization header
  └─ validates JWT signature and expiry (jjwt)
  └─ checks JTI against Redis blacklist
  └─ if valid: sets authenticated user in SecurityContext
  └─ if invalid: passes through unauthenticated (Spring Security handles it next)
        │
        ▼
③ SecurityConfig chain     Spring Security rules
  └─ /auth/register, /auth/login, /auth/refresh → public
  └─ /admin/**             → ROLE_ADMIN only
  └─ everything else       → must be authenticated
  └─ no auth              → AuthEntryPoint returns 401 JSON
  └─ wrong role           → AccessDeniedHandler returns 403 JSON
        │
        ▼
④ AuthController           validates request body (@Valid)
  └─ delegates to AuthService
        │
        ▼
⑤ AuthService              business logic
  └─ talks to PostgreSQL via UserRepository
  └─ talks to Redis for tokens and blacklist
        │
        ▼
  HTTP Response
```

---

## Project Structure

```
src/main/java/ma/springsecurityboilerplate/
│
├── SpringSecurityBoilerplateApplication.java   Entry point
│
├── config/
│   ├── SecurityConfig.java      Filter chain, CORS, BCrypt, AuthManager, session policy
│   └── RedisConfig.java         StringRedisTemplate (strings) + byte[] template (Bucket4j)
│
├── controller/
│   └── AuthController.java      4 endpoints: register, login, refresh, logout
│                                Handles HttpOnly cookie lifecycle
│
├── dto/
│   └── AuthDtos.java            AuthRequest, RegisterRequest, AuthResponse, MessageResponse
│
├── entity/
│   ├── User.java                JPA entity + UserDetails (email, password, role, createdAt)
│   └── Role.java                Enum: ROLE_USER, ROLE_ADMIN
│
├── jwt/
│   ├── JwtConfig.java           Binds application.jwt.* from application.yml
│   ├── JwtTokenProvider.java    Generate, parse, validate tokens. Extract email/JTI/TTL
│   ├── JwtTokenBlacklist.java   Redis wrapper: write JTI on logout, check on every request
│   └── JwtAuthFilter.java       Reads Bearer token → validates → sets SecurityContext
│
├── ratelimit/
│   ├── RateLimitConfig.java     Bucket rules from application.yml (capacity, refill)
│   ├── RateLimitService.java    Bucket4j + Lettuce ProxyManager — one bucket per IP in Redis
│   └── RateLimitFilter.java     @Order(1) servlet filter — runs before everything else
│
├── repository/
│   └── UserRepository.java      findByEmail(), existsByEmail()
│
├── security/
│   ├── AuthEntryPoint.java          401 JSON response for unauthenticated requests
│   ├── CustomAccessDeniedHandler.java   403 JSON response for wrong role
│   └── GlobalExceptionHandler.java  @RestControllerAdvice — maps exceptions to HTTP responses
│
└── service/
    ├── AuthService.java         register, login, refresh rotation, logout, token pair builder
    └── UserService.java         UserDetailsService impl — loadUserByUsername for Spring Security
```

---

## API Endpoints

Base URL: `http://localhost:8080/api`

### POST `/auth/register`
Creates a new account with `ROLE_USER`.

**Request body:**
```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

**Response `201 Created`:**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```
Also sets `Set-Cookie: refresh_token=...; HttpOnly; Secure; Path=/api/auth/refresh`

---

### POST `/auth/login`
Authenticates an existing user.

**Request body:** same as register

**Response `200 OK`:** same shape as register response + refresh cookie

---

### POST `/auth/refresh`
Issues a new access token using the refresh token cookie.
No request body needed — the cookie is sent automatically by the browser.

**Response `200 OK`:**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```
Also rotates the refresh token cookie.

---

### POST `/auth/logout`
Revokes the current session.

**Header required:** `Authorization: Bearer <accessToken>`

**Response `200 OK`:**
```json
{
  "message": "Logged out successfully"
}
```

---

### Error responses

All errors return the same JSON shape:
```json
{
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid email or password",
  "timestamp": "2025-06-13T10:30:00Z"
}
```

| Scenario | Status |
|---|---|
| Wrong credentials | 401 |
| Missing / invalid token | 401 |
| Token blacklisted (after logout) | 401 |
| Valid token but wrong role | 403 |
| Email already registered | 409 |
| Validation failure (short password etc.) | 400 |
| Too many requests | 429 |

---

## Setup & Running

### Prerequisites
- Docker and Docker Compose installed
- Java 17 + Maven (only needed for local dev without Docker)

---

### Option A — Full Docker (recommended)

**1. Clone and configure**
```bash
cp .env.example .env
```

Open `.env` and set at minimum:
```env
SPRING_DATASOURCE_PASSWORD=yourpassword
APPLICATION_JWT_SECRET=this-must-be-at-least-64-characters-long-or-jjwt-will-throw-on-startup
```

**2. Build and run**
```bash
docker-compose up --build
```

Docker Compose will:
- Start PostgreSQL and wait for it to be healthy
- Start Redis and wait for it to be healthy
- Build the Spring Boot app from source
- Start the app only after both dependencies are ready

**3. Verify it's running**
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"test@test.com","password":"password123"}'
```

---

### Option B — Local dev (app only, infra in Docker)

**1. Start only PostgreSQL and Redis**
```bash
docker-compose up postgres redis -d
```

**2. Run the app with Maven**
```bash
./mvnw spring-boot:run
```

The app reads from `application.yml` defaults which point to `localhost:5432` and `localhost:6379`.

---

## Configuration Reference

All config lives in `application.yml` and can be overridden by environment variables.

| `application.yml` key | Env variable | Default | Description |
|---|---|---|---|
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | localhost/authdb | PostgreSQL URL |
| `spring.datasource.username` | `SPRING_DATASOURCE_USERNAME` | postgres | DB username |
| `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD` | — | DB password |
| `spring.data.redis.host` | `SPRING_DATA_REDIS_HOST` | localhost | Redis host |
| `spring.data.redis.port` | `SPRING_DATA_REDIS_PORT` | 6379 | Redis port |
| `application.jwt.secret` | `APPLICATION_JWT_SECRET` | fallback (dev only) | Must be 64+ chars |
| `application.jwt.access-token-expiry` | `APPLICATION_JWT_ACCESS_TOKEN_EXPIRY` | 900000 (15 min) | In milliseconds |
| `application.jwt.refresh-token-expiry` | `APPLICATION_JWT_REFRESH_TOKEN_EXPIRY` | 604800000 (7 days) | In milliseconds |
| `application.rate-limit.capacity` | `APPLICATION_RATE_LIMIT_CAPACITY` | 20 | Max tokens in bucket |
| `application.rate-limit.refill-tokens` | `APPLICATION_RATE_LIMIT_REFILL_TOKENS` | 20 | Tokens added per refill |
| `application.rate-limit.refill-duration-seconds` | `APPLICATION_RATE_LIMIT_REFILL_DURATION_SECONDS` | 60 | Refill interval |

---

## Using It in a New Project

**1. Copy these packages** — they have no cross-dependencies between them:
```
jwt/          self-contained: jjwt + Redis only
ratelimit/    self-contained: Bucket4j + Lettuce + Redis only
security/     self-contained: exception handlers and entry points
```

**2. Copy the rest:**
```
config/, controller/, service/, dto/, entity/, repository/
```

**3. Find and replace** the base package name:
```
ma.springsecurityboilerplate → com.yourcompany.yourproject
```

**4. Checklist before going live:**
- [ ] JWT secret is 64+ characters and comes from an env var or secrets manager — never hardcoded
- [ ] `cookie.setSecure(true)` is already on — confirm your production environment uses HTTPS
- [ ] Update `allowedOrigins` in `SecurityConfig` to your real frontend URL
- [ ] Set a Redis password in production and add it to `application.yml`
- [ ] Tune `rate-limit.capacity` and `refill-tokens` to match your expected traffic
- [ ] Delete `application.properties` if it exists alongside `application.yml` — Spring loads both and one can silently override the other
- [ ] Add `spring-boot-starter-actuator` to `pom.xml` if you want the Dockerfile health check to work

---

## Production Hardening Checklist

- [ ] JWT secret via secrets manager (AWS Secrets Manager, Vault, etc.)
- [ ] PostgreSQL on a private network, not exposed on port 5432
- [ ] Redis AUTH password configured
- [ ] HTTPS enforced — the refresh cookie is `Secure` and will not be sent over HTTP
- [ ] CORS locked to your actual frontend origin, not `localhost`
- [ ] BCrypt strength at 12 (already the default)
- [ ] Rate limit tuned — 20 requests/60s is conservative, adjust for your use case
- [ ] Consider adding `spring-boot-starter-actuator` for health and metrics endpoints
- [ ] Actuator endpoints secured or restricted to internal network only if added