package com.buslink.security;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.buslink.entity.Admin;
import com.buslink.entity.Conductor;
import com.buslink.entity.User;
import com.buslink.enums.AdminStatus;
import com.buslink.enums.ConductorStatus;
import com.buslink.enums.UserStatus;
import com.buslink.exception.ValidationException;
import io.jsonwebtoken.Claims;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;

class JwtUtilTest {

    private static final String TEST_SECRET = "qvIHDY/EOwvtryJOP7bgNNmw2xWkKESL0J/gQE79KTE=";
    private static final long ONE_DAY_MS = 86_400_000L;

    @Test
    void generateAccessToken_extractUsername_roundTrips() {
        JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, ONE_DAY_MS, ONE_DAY_MS);
        User user = User.builder().email("rider@buslink.com").build();

        String token = jwtUtil.generateAccessToken(user);

        assertThat(jwtUtil.extractUsername(token)).isEqualTo("rider@buslink.com");
    }

    @Test
    void isTokenValid_returnsTrue_forFreshTokenAndMatchingUser() {
        JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, ONE_DAY_MS, ONE_DAY_MS);
        User user = User.builder().email("rider@buslink.com").build();
        UserDetails userDetails = userDetailsFor("rider@buslink.com");

        String token = jwtUtil.generateAccessToken(user);

        assertThat(jwtUtil.isTokenValid(token, userDetails)).isTrue();
    }

    @Test
    void isTokenValid_returnsFalse_whenUsernameDoesNotMatch() {
        JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, ONE_DAY_MS, ONE_DAY_MS);
        User user = User.builder().email("rider@buslink.com").build();
        UserDetails otherUser = userDetailsFor("someone-else@buslink.com");

        String token = jwtUtil.generateAccessToken(user);

        assertThat(jwtUtil.isTokenValid(token, otherUser)).isFalse();
    }

    @Test
    void isTokenValid_returnsFalse_forExpiredToken() {
        JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, -1_000L, ONE_DAY_MS);
        User user = User.builder().email("rider@buslink.com").build();
        UserDetails userDetails = userDetailsFor("rider@buslink.com");

        String token = jwtUtil.generateAccessToken(user);

        assertThat(jwtUtil.isTokenValid(token, userDetails)).isFalse();
    }

    @Test
    void isTokenValid_returnsFalse_forTamperedToken() {
        JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, ONE_DAY_MS, ONE_DAY_MS);
        User user = User.builder().email("rider@buslink.com").build();
        UserDetails userDetails = userDetailsFor("rider@buslink.com");
        String token = jwtUtil.generateAccessToken(user);
        // Tamper a character well inside the header segment rather than the token's
        // last character. Base64url's final character in a segment can carry
        // "don't-care" padding bits that some decoders ignore, so a small fraction of
        // last-character swaps decode to byte-identical content and leave the
        // signature valid — flaky, not a real security gap. A middle-of-segment
        // character always changes the decoded bytes, so this is deterministic.
        int tamperIndex = 5;
        char originalChar = token.charAt(tamperIndex);
        char tamperedChar = originalChar == 'a' ? 'b' : 'a';
        String tamperedToken = token.substring(0, tamperIndex) + tamperedChar + token.substring(tamperIndex + 1);

        assertThat(jwtUtil.isTokenValid(tamperedToken, userDetails)).isFalse();
    }

    @Test
    void extractClaim_readsCustomTypeClaim() {
        JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, ONE_DAY_MS, ONE_DAY_MS);
        User user = User.builder().email("rider@buslink.com").build();

        String accessToken = jwtUtil.generateAccessToken(user);
        String refreshToken = jwtUtil.generateRefreshToken(user);

        assertThat(jwtUtil.extractClaim(accessToken, (Claims c) -> c.get("type", String.class))).isEqualTo("access");
        assertThat(jwtUtil.extractClaim(refreshToken, (Claims c) -> c.get("type", String.class))).isEqualTo("refresh");
    }

    @Test
    void extractRole_roundTrips_forPassengerAndConductorTokens() {
        JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, ONE_DAY_MS, ONE_DAY_MS);
        User user = User.builder().email("rider@buslink.com").build();
        Conductor conductor = Conductor.builder()
                .email("conductor@buslink.com")
                .status(ConductorStatus.ACTIVE)
                .build();

        String userAccessToken = jwtUtil.generateAccessToken(user);
        String userRefreshToken = jwtUtil.generateRefreshToken(user);
        String conductorAccessToken = jwtUtil.generateConductorAccessToken(conductor);
        String conductorRefreshToken = jwtUtil.generateConductorRefreshToken(conductor);

        assertThat(jwtUtil.extractRole(userAccessToken)).isEqualTo("PASSENGER");
        assertThat(jwtUtil.extractRole(userRefreshToken)).isEqualTo("PASSENGER");
        assertThat(jwtUtil.extractRole(conductorAccessToken)).isEqualTo("CONDUCTOR");
        assertThat(jwtUtil.extractRole(conductorRefreshToken)).isEqualTo("CONDUCTOR");
    }

    @Test
    void extractRole_roundTrips_forAdminTokens() {
        JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, ONE_DAY_MS, ONE_DAY_MS);
        Admin admin = Admin.builder()
                .email("admin@buslink.com")
                .status(AdminStatus.ACTIVE)
                .build();

        String adminAccessToken = jwtUtil.generateAdminAccessToken(admin);
        String adminRefreshToken = jwtUtil.generateAdminRefreshToken(admin);

        assertThat(jwtUtil.extractRole(adminAccessToken)).isEqualTo("ADMIN");
        assertThat(jwtUtil.extractRole(adminRefreshToken)).isEqualTo("ADMIN");
    }

    @Test
    void generatePassengerQrToken_roundTrips_userIdAndDeviceId() {
        JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, ONE_DAY_MS, ONE_DAY_MS);
        UUID userId = UUID.randomUUID();
        User user = User.builder()
                .userId(userId)
                .deviceId("device-123")
                .status(UserStatus.ACTIVE)
                .build();

        String qrToken = jwtUtil.generatePassengerQrToken(user);

        assertThat(jwtUtil.extractUserIdFromPassengerQr(qrToken)).isEqualTo(userId);
        assertThat(jwtUtil.extractDeviceIdFromPassengerQr(qrToken)).isEqualTo("device-123");
        assertThat(jwtUtil.validatePassengerQrToken(qrToken).get("userStatus", String.class))
                .isEqualTo("ACTIVE");
        assertThat(jwtUtil.validatePassengerQrToken(qrToken).get("type", String.class))
                .isEqualTo("PASSENGER_QR");
    }

    @Test
    void validatePassengerQrToken_throws_forWrongTokenType() {
        // Passenger QR expiry is a fixed 24h constant inside JwtUtil (not driven by the
        // constructor's access/refresh expiry params), so a genuinely expired QR token
        // can't be produced here the way isTokenValid_returnsFalse_forExpiredToken does
        // for access tokens — the type-claim check below exercises the same rejection
        // path (parses fine, fails validatePassengerQrToken's own guard).
        JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, ONE_DAY_MS, ONE_DAY_MS);
        User user = User.builder()
                .userId(UUID.randomUUID())
                .deviceId("device-123")
                .status(UserStatus.ACTIVE)
                .build();

        String accessToken = jwtUtil.generateAccessToken(user);

        assertThatThrownBy(() -> jwtUtil.validatePassengerQrToken(accessToken))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void validatePassengerQrToken_throws_forTamperedToken() {
        JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, ONE_DAY_MS, ONE_DAY_MS);
        User user = User.builder()
                .userId(UUID.randomUUID())
                .deviceId("device-123")
                .status(UserStatus.ACTIVE)
                .build();
        String qrToken = jwtUtil.generatePassengerQrToken(user);
        int tamperIndex = 5;
        char originalChar = qrToken.charAt(tamperIndex);
        char tamperedChar = originalChar == 'a' ? 'b' : 'a';
        String tamperedToken = qrToken.substring(0, tamperIndex) + tamperedChar + qrToken.substring(tamperIndex + 1);

        assertThatThrownBy(() -> jwtUtil.validatePassengerQrToken(tamperedToken))
                .isInstanceOf(ValidationException.class);
    }

    // QR validity is checked at BOARDING time for offline sync, not at sync time. A fresh QR has
    // exp = now + 24h, so "boarding" instants are expressed relative to now: within the window,
    // just past exp but inside the skew tolerance, and well past exp.

    @Test
    void extractUserIdFromPassengerQrAt_acceptsBoardingTimeInsideQrWindow() {
        JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, ONE_DAY_MS, ONE_DAY_MS);
        UUID userId = UUID.randomUUID();
        String qrToken = jwtUtil.generatePassengerQrToken(passengerUser(userId));

        Instant boarding = Instant.now().plus(23, ChronoUnit.HOURS);

        assertThat(jwtUtil.extractUserIdFromPassengerQrAt(qrToken, boarding, Duration.ofMinutes(5)))
                .isEqualTo(userId);
    }

    @Test
    void extractUserIdFromPassengerQrAt_acceptsBoardingJustPastExpWithinClockSkew() {
        JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, ONE_DAY_MS, ONE_DAY_MS);
        UUID userId = UUID.randomUUID();
        String qrToken = jwtUtil.generatePassengerQrToken(passengerUser(userId));

        Instant boarding = Instant.now().plus(24, ChronoUnit.HOURS).plus(2, ChronoUnit.MINUTES);

        assertThat(jwtUtil.extractUserIdFromPassengerQrAt(qrToken, boarding, Duration.ofMinutes(5)))
                .isEqualTo(userId);
    }

    @Test
    void extractUserIdFromPassengerQrAt_rejectsBoardingAfterQrExpired() {
        JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, ONE_DAY_MS, ONE_DAY_MS);
        String qrToken = jwtUtil.generatePassengerQrToken(passengerUser(UUID.randomUUID()));

        Instant boarding = Instant.now().plus(25, ChronoUnit.HOURS);

        assertThatThrownBy(() -> jwtUtil.extractUserIdFromPassengerQrAt(qrToken, boarding, Duration.ofMinutes(5)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void extractUserIdFromPassengerQrAt_stillRejectsTamperedToken() {
        JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, ONE_DAY_MS, ONE_DAY_MS);
        String qrToken = jwtUtil.generatePassengerQrToken(passengerUser(UUID.randomUUID()));
        char originalChar = qrToken.charAt(5);
        String tamperedToken = qrToken.substring(0, 5) + (originalChar == 'a' ? 'b' : 'a') + qrToken.substring(6);

        assertThatThrownBy(() -> jwtUtil.extractUserIdFromPassengerQrAt(
                        tamperedToken, Instant.now(), Duration.ofMinutes(5)))
                .isInstanceOf(ValidationException.class);
    }

    private static User passengerUser(UUID userId) {
        return User.builder()
                .userId(userId)
                .deviceId("device-123")
                .status(UserStatus.ACTIVE)
                .build();
    }

    private static UserDetails userDetailsFor(String email) {
        return new org.springframework.security.core.userdetails.User(email, "unused", Collections.emptyList());
    }
}
