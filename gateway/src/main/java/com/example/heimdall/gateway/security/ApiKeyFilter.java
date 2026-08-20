package com.example.heimdall.gateway.security;

import com.example.heimdall.gateway.config.GatewayProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Requires a valid {@code X-API-Key} header on state-changing requests to
 * {@code /objects/**} (uploads and deletes). Reads stay open, the same way a
 * CDN or video streaming endpoint typically is - anyone can watch, only
 * authorized callers can publish or remove content.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-API-Key";
    private static final Set<String> PROTECTED_METHODS = Set.of(HttpMethod.POST.name(), HttpMethod.DELETE.name());

    private final GatewayProperties properties;

    public ApiKeyFilter(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean needsAuth = request.getRequestURI().startsWith("/objects")
                && PROTECTED_METHODS.contains(request.getMethod());

        if (needsAuth) {
            String provided = request.getHeader(HEADER);
            if (provided == null || !constantTimeEquals(provided, properties.getApiKey())) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write(
                        "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Missing or invalid " + HEADER + " header\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
