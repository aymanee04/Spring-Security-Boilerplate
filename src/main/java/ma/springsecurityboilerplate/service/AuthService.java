package ma.springsecurityboilerplate.service;


import lombok.RequiredArgsConstructor;
import ma.springsecurityboilerplate.dto.AuthDtos;
import ma.springsecurityboilerplate.entity.Role;
import ma.springsecurityboilerplate.entity.User;
import ma.springsecurityboilerplate.jwt.JwtConfig;
import ma.springsecurityboilerplate.jwt.JwtTokenBlacklist;
import ma.springsecurityboilerplate.jwt.JwtTokenProvider;
import ma.springsecurityboilerplate.repository.UserRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String REFRESH_PREFIX = "refresh:";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final JwtTokenBlacklist blacklist;
    private final JwtConfig jwtConfig;
    private final StringRedisTemplate redisTemplate;

    @Transactional
    public TokenPair register(AuthDtos.RegisterRequest req) {
        if (userRepository.existsByEmail(req.getEmail())) {
            throw new IllegalStateException("Email already in use: " + req.getEmail());
        }
        User user = User.builder()
                .email(req.getEmail())
                .password(passwordEncoder.encode(req.getPassword()))
                .role(Role.ROLE_USER)
                .build();
        userRepository.save(user);
        return buildTokenPair(user);
    }

    public TokenPair login(AuthDtos.AuthRequest req) {
        // Throws BadCredentialsException on failure — caught by GlobalExceptionHandler.
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.getEmail(), req.getPassword()));

        User user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        return buildTokenPair(user);
    }

    // Refresh token rotation: every successful refresh issues a brand-new refresh token
    // and invalidates the old one. A replayed stolen token will fail because it won't
    // match the value stored in Redis anymore.
    public TokenPair refresh(String refreshToken) {
        if (!tokenProvider.isValid(refreshToken)) {
            throw new IllegalArgumentException("Invalid or expired refresh token");
        }

        String email = tokenProvider.extractEmail(refreshToken);
        String stored = redisTemplate.opsForValue().get(REFRESH_PREFIX + email);

        if (!refreshToken.equals(stored)) {
            // Possible token reuse attack — invalidate all sessions for this user.
            redisTemplate.delete(REFRESH_PREFIX + email);
            throw new IllegalArgumentException("Refresh token reuse detected — please log in again");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        return buildTokenPair(user);
    }

    public void logout(String accessToken, String email) {
        long remainingTtl = tokenProvider.getRemainingTtlMs(accessToken);
        if (remainingTtl > 0) {
            blacklist.blacklist(tokenProvider.extractJti(accessToken), remainingTtl);
        }
        redisTemplate.delete(REFRESH_PREFIX + email);
    }

    // ── internal ────────────────────────────────────────────────────────────

    private TokenPair buildTokenPair(User user) {
        String accessToken  = tokenProvider.generateAccessToken(user);
        String refreshToken = tokenProvider.generateRefreshToken(user);

        redisTemplate.opsForValue().set(
                REFRESH_PREFIX + user.getEmail(),
                refreshToken,
                Duration.ofMillis(jwtConfig.getRefreshTokenExpiry()));

        return new TokenPair(
                new AuthDtos.AuthResponse(accessToken, jwtConfig.getAccessTokenExpiry()),
                refreshToken);
    }

    public record TokenPair(AuthDtos.AuthResponse authResponse, String refreshToken) {}
}
