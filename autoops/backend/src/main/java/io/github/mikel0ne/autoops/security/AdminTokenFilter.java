package io.github.mikel0ne.autoops.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class AdminTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Admin-Token";

    private final byte[] expected;

    public AdminTokenFilter(String token) {
        this.expected = token == null ? new byte[0] : token.strip().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        // Fails closed: without a configured token the admin endpoints are unavailable.
        boolean authorised = expected.length > 0 && given != null
                && MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8));
        if (!authorised) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }
}
