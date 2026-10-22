package com.fairtix.auth.api;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.view.RedirectView;

import com.fairtix.auth.application.AuthService;
import com.fairtix.auth.application.EmailVerificationService;
import com.fairtix.auth.application.JwtService;
import com.fairtix.auth.application.PasswordResetService;
import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.auth.domain.RefreshToken;
import com.fairtix.auth.infrastructure.RefreshTokenRepository;
import com.fairtix.users.dto.AuthResponse;
import com.fairtix.users.dto.ForgotPasswordRequest;
import com.fairtix.users.dto.LoginRequest;
import com.fairtix.users.dto.RegisterRequest;
import com.fairtix.users.dto.ResetPasswordRequest;
import com.fairtix.users.infrastructure.UserRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/**
 * Handles user authentication — registration, login, session queries, and logout.
 *
 * <p>Login and register set an HTTP-only cookie containing the JWT.
 * The response body returns user info (no token).
 */
@Tag(name = "Auth", description = "Registration, login, and session management")
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final String ACCESS_COOKIE = "fairtix_token";
    private static final String REFRESH_COOKIE = "fairtix_refresh";
    private static final int ACCESS_MAX_AGE = 900;          // 15 minutes
    private static final int REFRESH_MAX_AGE = 7 * 24 * 3600; // 7 days

    private final AuthService service;
    private final JwtService jwtService;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${app.cookie.secure:false}")
    private boolean cookieSecure;

    @Value("${app.base-url:http://localhost:3000}")
    private String baseUrl;

    public AuthController(AuthService service,
                          JwtService jwtService,
                          EmailVerificationService emailVerificationService,
                          PasswordResetService passwordResetService,
                          UserRepository userRepository,
                          RefreshTokenRepository refreshTokenRepository) {
        this.service = service;
        this.jwtService = jwtService;
        this.emailVerificationService = emailVerificationService;
        this.passwordResetService = passwordResetService;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Operation(summary = "Register a new user",
            description = "Creates a user account, sets an HTTP-only auth cookie, and returns user info.")
    @ApiResponse(responseCode = "200", description = "Registration successful")
    @ApiResponse(responseCode = "400", description = "Invalid email or password")
    @ApiResponse(responseCode = "409", description = "Email already in use")
    @SecurityRequirements
    @PostMapping("/register")
    // Deliberately not @Transactional, like login: AuthService.register commits the
    // user before the verification token and email, and anyone can call this.
    public AuthResponse register(@Valid @RequestBody RegisterRequest request, HttpServletResponse response) {
        String jwt = service.register(request);
        var claims = jwtService.extractAllClaims(jwt);
        UUID userId = UUID.fromString(claims.get("userId", String.class));

        String rawRefresh = jwtService.generateRefreshToken(userId);
        addAccessCookie(response, jwt);
        addRefreshCookie(response, rawRefresh);
        return buildAuthResponse(jwt);
    }

    @Operation(summary = "Log in",
            description = "Authenticates with email/password, sets HTTP-only access and refresh token cookies, and returns user info.")
    @ApiResponse(responseCode = "200", description = "Login successful")
    @ApiResponse(responseCode = "401", description = "Invalid credentials")
    @SecurityRequirements
    @PostMapping("/login")
    // Deliberately not @Transactional: a failed login writes a REQUIRES_NEW audit
    // row, and holding an outer connection meanwhile lets unauthenticated callers
    // starve the pool. generateRefreshToken has its own transaction.
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        String jwt = service.login(request);
        var claims = jwtService.extractAllClaims(jwt);
        UUID userId = UUID.fromString(claims.get("userId", String.class));

        String rawRefresh = jwtService.generateRefreshToken(userId);
        addAccessCookie(response, jwt);
        addRefreshCookie(response, rawRefresh);
        return buildAuthResponse(jwt);
    }

    @Operation(summary = "Get current user",
            description = "Returns the authenticated user's info based on the auth cookie or bearer token.")
    @ApiResponse(responseCode = "200", description = "Authenticated")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/me")
    public AuthResponse me(@AuthenticationPrincipal CustomUserPrincipal principal) {
        String role = principal.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .findFirst()
                .orElse("USER");
        boolean emailVerified = userRepository.findById(principal.getUserId())
                .map(u -> u.isEmailVerified())
                .orElse(false);
        return new AuthResponse(principal.getUserId(), principal.getUsername(), role, emailVerified);
    }

    @Operation(summary = "Refresh access token",
            description = "Exchanges a valid refresh token cookie for a new access + refresh token pair.")
    @ApiResponse(responseCode = "200", description = "Tokens refreshed")
    @ApiResponse(responseCode = "401", description = "Refresh token missing, invalid, expired, or revoked")
    @SecurityRequirements
    @PostMapping("/refresh")
    @Transactional
    public AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        String rawToken = extractCookieValue(request, REFRESH_COOKIE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No refresh token"));

        String hash = jwtService.hashRefreshToken(rawToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));

        if (stored.isRevoked() || stored.isExpired()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token expired or revoked");
        }

        // Rotate: revoke old, issue new
        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

        UUID userId = stored.getUserId();
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        String newJwt = jwtService.generateToken(userId, user.getEmail(), user.getRole().name());
        String newRawRefresh = jwtService.generateRefreshToken(userId);

        addAccessCookie(response, newJwt);
        addRefreshCookie(response, newRawRefresh);
        return buildAuthResponse(newJwt);
    }

    @Operation(summary = "Verify email address",
            description = "Validates the email verification token and marks the user's email as verified.")
    @ApiResponse(responseCode = "302", description = "Redirect to frontend with result")
    @SecurityRequirements
    @GetMapping("/verify")
    public RedirectView verifyEmail(@RequestParam("token") String token) {
        try {
            emailVerificationService.verifyToken(token);
            return new RedirectView(baseUrl + "/verify?success=true");
        } catch (Exception e) {
            return new RedirectView(baseUrl + "/verify?error=true");
        }
    }

    @Operation(summary = "Resend verification email",
            description = "Sends a new verification email for the authenticated user.")
    @ApiResponse(responseCode = "204", description = "Email sent if account exists and is unverified")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resendVerification(@AuthenticationPrincipal CustomUserPrincipal principal) {
        userRepository.findById(principal.getUserId()).ifPresent(user -> {
            if (!user.isEmailVerified() && !user.isDeleted()
                    && emailVerificationService.resendAllowed(user.getId())) {
                try {
                    emailVerificationService.sendVerificationEmail(user);
                } catch (Exception ignored) {
                    // Swallow — don't leak whether send succeeded
                }
            }
        });
    }

    @Operation(summary = "Request password reset",
            description = "Sends a password reset email if the address is registered. Always returns 204 to prevent email enumeration.")
    @ApiResponse(responseCode = "204", description = "Reset email sent if account exists")
    @SecurityRequirements
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        try {
            passwordResetService.requestReset(request.email());
        } catch (Exception e) {
            // Swallow rate-limit and all other errors — never reveal account existence
            // Re-throw only rate-limit so clients get 429 feedback without leaking data
            if (e instanceof org.springframework.web.server.ResponseStatusException rse
                    && rse.getStatusCode().value() == 429) {
                throw rse;
            }
        }
    }

    @Operation(summary = "Reset password",
            description = "Resets the user's password using a valid time-limited token.")
    @ApiResponse(responseCode = "204", description = "Password reset successfully")
    @ApiResponse(responseCode = "400", description = "Invalid, expired, or already-used token, or weak password")
    @SecurityRequirements
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.token(), request.newPassword());
    }

    @Operation(summary = "Log out",
            description = "Revokes the refresh token and clears both auth cookies.")
    @ApiResponse(responseCode = "204", description = "Logged out")
    @SecurityRequirements
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        extractCookieValue(request, REFRESH_COOKIE).ifPresent(raw -> {
            String hash = jwtService.hashRefreshToken(raw);
            refreshTokenRepository.findByTokenHash(hash).ifPresent(token -> {
                token.setRevoked(true);
                refreshTokenRepository.save(token);
            });
        });
        clearCookie(response, ACCESS_COOKIE, "/");
        clearCookie(response, REFRESH_COOKIE, "/auth");
    }

    // --- helpers ---

    private void addAccessCookie(HttpServletResponse response, String jwt) {
        ResponseCookie cookie = ResponseCookie.from(ACCESS_COOKIE, jwt)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSecure ? "None" : "Lax")
                .path("/")
                .maxAge(ACCESS_MAX_AGE)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void addRefreshCookie(HttpServletResponse response, String rawToken) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, rawToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSecure ? "None" : "Lax")
                .path("/auth")
                .maxAge(REFRESH_MAX_AGE)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearCookie(HttpServletResponse response, String name, String path) {
        ResponseCookie cookie = ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSecure ? "None" : "Lax")
                .path(path)
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private Optional<String> extractCookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return Optional.empty();
        return Arrays.stream(request.getCookies())
                .filter(c -> name.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    private AuthResponse buildAuthResponse(String jwt) {
        var claims = jwtService.extractAllClaims(jwt);
        UUID userId = UUID.fromString(claims.get("userId", String.class));
        boolean emailVerified = userRepository.findById(userId)
                .map(u -> u.isEmailVerified())
                .orElse(false);
        return new AuthResponse(
                userId,
                claims.getSubject(),
                claims.get("role", String.class),
                emailVerified);
    }
}
