package com.buslink.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTH_PATH_PREFIX = "/auth/";
    private static final String CONDUCTOR_AUTH_PATH_PREFIX = "/conductor/auth/";
    private static final String ROLE_CONDUCTOR = "CONDUCTOR";
    private static final String ROLE_ADMIN = "ADMIN";

    private final JwtUtil jwtUtil;
    private final UserDetailsServiceImpl userDetailsServiceImpl;
    private final ConductorDetailsServiceImpl conductorDetailsServiceImpl;
    private final AdminDetailsServiceImpl adminDetailsServiceImpl;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith(AUTH_PATH_PREFIX) || path.startsWith(CONDUCTOR_AUTH_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader(AUTH_HEADER);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        if (jwtUtil.isRefreshToken(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {

            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                String email = jwtUtil.extractUsername(token);
                String role = jwtUtil.extractRole(token);
                UserDetails userDetails;
                if (ROLE_CONDUCTOR.equals(role)) {
                    userDetails = conductorDetailsServiceImpl.loadUserByUsername(email);
                } else if (ROLE_ADMIN.equals(role)) {
                    userDetails = adminDetailsServiceImpl.loadUserByUsername(email);
                } else {
                    userDetails = userDetailsServiceImpl.loadUserByUsername(email);
                }

                if (jwtUtil.isTokenValid(token, userDetails)) {
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (JwtException | IllegalArgumentException | UsernameNotFoundException e) {
            log.debug("JWT authentication skipped: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
