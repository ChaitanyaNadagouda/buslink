package com.buslink.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.buslink.entity.Admin;
import com.buslink.entity.Conductor;
import com.buslink.entity.User;
import com.buslink.enums.AdminStatus;
import com.buslink.enums.ConductorStatus;
import io.jsonwebtoken.Claims;
import java.util.Collections;
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

    private static UserDetails userDetailsFor(String email) {
        return new org.springframework.security.core.userdetails.User(email, "unused", Collections.emptyList());
    }
}
