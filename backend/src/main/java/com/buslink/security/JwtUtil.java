package com.buslink.security;

import com.buslink.entity.Conductor;
import com.buslink.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import java.util.function.Function;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

@Component
public class JwtUtil {

    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_ROLE = "role";
    private static final String TOKEN_TYPE_ACCESS = "access";
    private static final String TOKEN_TYPE_REFRESH = "refresh";
    private static final String ROLE_PASSENGER = "PASSENGER";
    private static final String ROLE_CONDUCTOR = "CONDUCTOR";

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
