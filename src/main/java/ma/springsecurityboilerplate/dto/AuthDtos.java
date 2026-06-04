package ma.springsecurityboilerplate.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

public final class AuthDtos {

    private AuthDtos() {}

    @Data
    public static class AuthRequest {
        @NotBlank @Email
        private String email;

        @NotBlank
        private String password;
    }

    @Data
    public static class RegisterRequest {
        @NotBlank @Email
        private String email;

        @NotBlank @Size(min = 8, message = "Password must be at least 8 characters")
        private String password;
    }

    // Only the access token is returned in the body.
    // The refresh token travels via HttpOnly cookie (set in AuthController).
    @Data
    public static class AuthResponse {
        private final String accessToken;
        private final String tokenType = "Bearer";
        private final long expiresIn;

        public AuthResponse(String accessToken, long expiresInMs) {
            this.accessToken = accessToken;
            this.expiresIn = expiresInMs / 1000;
        }
    }

    @Data
    public static class MessageResponse {
        private final String message;
    }
}
