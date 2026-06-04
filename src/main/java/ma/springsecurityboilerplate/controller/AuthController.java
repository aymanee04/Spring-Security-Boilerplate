package ma.springsecurityboilerplate.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ma.springsecurityboilerplate.dto.AuthDtos;
import ma.springsecurityboilerplate.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String REFRESH_COOKIE = "refresh_token";
    private final AuthService authService;

    // Register creates the user, issues an access token in the body, and sets
    // the refresh token as an HttpOnly cookie — same shape as /login so the
    // client handles both responses identically.
    @PostMapping("/register")
    public ResponseEntity<AuthDtos.AuthResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest request,
                                                          HttpServletResponse response) {
        AuthService.TokenPair pair = authService.register(request);
        addRefreshCookie(response, pair.refreshToken());
        return ResponseEntity.status(HttpStatus.CREATED).body(pair.authResponse());
    }

    @PostMapping("/login")
    public ResponseEntity<AuthDtos.AuthResponse> login(@Valid @RequestBody AuthDtos.AuthRequest request,
                                                       HttpServletResponse response) {
        AuthService.TokenPair pair = authService.login(request);
        addRefreshCookie(response, pair.refreshToken());
        return ResponseEntity.ok(pair.authResponse());
    }

    // The refresh token arrives only via HttpOnly cookie, never via the request body,
    // so JavaScript cannot read or forward it — this is the CSRF-safe design.
    @PostMapping("/refresh")
    public ResponseEntity<AuthDtos.AuthResponse> refresh(HttpServletRequest request,
                                                         HttpServletResponse response) {
        String refreshToken = extractRefreshCookie(request);
        AuthService.TokenPair pair = authService.refresh(refreshToken);
        addRefreshCookie(response, pair.refreshToken());   // rotate cookie
        return ResponseEntity.ok(pair.authResponse());
    }

    @PostMapping("/logout")
    public ResponseEntity<AuthDtos.MessageResponse> logout(HttpServletRequest request,
                                                           HttpServletResponse response,
                                                           @AuthenticationPrincipal UserDetails userDetails) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            authService.logout(authHeader.substring(7), userDetails.getUsername());
        }
        clearRefreshCookie(response);
        return ResponseEntity.ok(new AuthDtos.MessageResponse("Logged out successfully"));
    }

    // ── cookie helpers ──────────────────────────────────────────────────────

    // Path is scoped to /auth/refresh so the browser only sends the cookie to that
    // single endpoint — not to every API call, which would expose it unnecessarily.
    private void addRefreshCookie(HttpServletResponse response, String token) {
        Cookie cookie = new Cookie(REFRESH_COOKIE, token);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);          // set to false for local HTTP dev if needed
        cookie.setPath("/api/auth/refresh");
        cookie.setMaxAge(7 * 24 * 60 * 60);
        response.addCookie(cookie);
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(REFRESH_COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/api/auth/refresh");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    private String extractRefreshCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            throw new IllegalArgumentException("No refresh token cookie present");
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> REFRESH_COOKIE.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Refresh token cookie missing"));
    }
}
