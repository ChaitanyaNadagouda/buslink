package com.buslink.security;

import com.buslink.entity.Admin;
import com.buslink.entity.Conductor;
import com.buslink.entity.User;
import com.buslink.exception.ValidationException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import java.util.function.Function;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

@Component
public class JwtUtil {

    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_USER_STATUS = "userStatus";
    private static final String CLAIM_DEVICE_ID = "deviceId";
    private static final String TOKEN_TYPE_ACCESS = "access";
    private static final String TOKEN_TYPE_REFRESH = "refresh";
    private static final String TOKEN_TYPE_PASSENGER_QR = "PASSENGER_QR";
    private static final long PASSENGER_QR_EXPIRY_HOURS = 24;
    private static final String ROLE_PASSENGER = "PASSENGER";
    private static final String ROLE_CONDUCTOR = "CONDUCTOR";
    private static final String ROLE_ADMIN = "ADMIN";

    private final SecretKey signingKey;
    private final long accessTokenExpiryMs;
    private final long refreshTokenExpiryMs;

    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiry-ms}") long accessTokenExpiryMs,
            @Value("${jwt.refresh-token-expiry-ms}") long refreshTokenExpiryMs) {
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.accessTokenExpiryMs = accessTokenExpiryMs;
        this.refreshTokenExpiryMs = refreshTokenExpiryMs;
    }

    public String generateAccessToken(User user) {
        return buildToken(user.getEmail(), accessTokenExpiryMs, TOKEN_TYPE_ACCESS, ROLE_PASSENGER);
    }

    public String generateRefreshToken(User user) {
        return buildToken(user.getEmail(), refreshTokenExpiryMs, TOKEN_TYPE_REFRESH, ROLE_PASSENGER);
    }

    public String generateConductorAccessToken(Conductor conductor) {
        return buildToken(conductor.getEmail(), accessTokenExpiryMs, TOKEN_TYPE_ACCESS, ROLE_CONDUCTOR);
    }

    public String generateConductorRefreshToken(Conductor conductor) {
        return buildToken(conductor.getEmail(), refreshTokenExpiryMs, TOKEN_TYPE_REFRESH, ROLE_CONDUCTOR);
    }

    public String generateAdminAccessToken(Admin admin) {
        return buildToken(admin.getEmail(), accessTokenExpiryMs, TOKEN_TYPE_ACCESS, ROLE_ADMIN);
    }

    public String generateAdminRefreshToken(Admin admin) {
        return buildToken(admin.getEmail(), refreshTokenExpiryMs, TOKEN_TYPE_REFRESH, ROLE_ADMIN);
    }

    public String generatePassengerQrToken(User user) {
        Date now = new Date();
        Date expiry = Date.from(now.toInstant().plus(PASSENGER_QR_EXPIRY_HOURS, ChronoUnit.HOURS));
        return Jwts.builder()
                .claim(CLAIM_USER_ID, user.getUserId().toString())
                .claim(CLAIM_USER_STATUS, user.getStatus().name())
                .claim(CLAIM_DEVICE_ID, user.getDeviceId())
                .claim(CLAIM_TYPE, TOKEN_TYPE_PASSENGER_QR)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    public Claims validatePassengerQrToken(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new ValidationException("Invalid or expired passenger QR token");
        }
        if (!TOKEN_TYPE_PASSENGER_QR.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new ValidationException("Invalid or expired passenger QR token");
        }
        return claims;
    }

    public UUID extractUserIdFromPassengerQr(String token) {
        return UUID.fromString(validatePassengerQrToken(token).get(CLAIM_USER_ID, String.class));
    }

    public String extractDeviceIdFromPassengerQr(String token) {
        return validatePassengerQrToken(token).get(CLAIM_DEVICE_ID, String.class);
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String extractRole(String token) {
        return extractClaim(token, claims -> claims.get(CLAIM_ROLE, String.class));
    }

    public boolean isRefreshToken(String token) {
        try {
            String type = extractClaim(token, claims -> claims.get(CLAIM_TYPE, String.class));
            return TOKEN_TYPE_REFRESH.equals(type);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            String username = extractUsername(token);
            return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claimsResolver.apply(claims);
    }

    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private String buildToken(String subject, long expiryMs, String type, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(subject)
                .claim(CLAIM_TYPE, type)
                .claim(CLAIM_ROLE, role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiryMs))
                .signWith(signingKey)
                .compact();
    }
}
