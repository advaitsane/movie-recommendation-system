package com.movies.catalog.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Logs every HTTP request with method, URL, status code, and response time, at a level
 * chosen by the status code (ERROR for 5xx, WARN for 4xx, INFO otherwise). Ordered to run
 * first in the filter chain for accurate timing.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // Record the start time
        long startTime = System.currentTimeMillis();

        // Log incoming request at debug level
        logger.debug("Incoming request: {} {} from {}",
                request.getMethod(),
                request.getRequestURI(),
                request.getRemoteAddr());

        try {
            // Continue with the filter chain
            filterChain.doFilter(request, response);
        } finally {
            // Calculate response time
            long responseTime = System.currentTimeMillis() - startTime;

            // Log the completed request with appropriate level based on status code
            logRequest(request.getMethod(), request.getRequestURI(), response.getStatus(), responseTime);
        }
    }

    private void logRequest(String method, String uri, int statusCode, long responseTime) {
        String message = String.format("%s %s %d - %dms", method, uri, statusCode, responseTime);

        if (statusCode >= 500) {
            logger.error(message);
        } else if (statusCode >= 400) {
            logger.warn(message);
        } else {
            logger.info(message);
        }
    }

    /**
     * Skip logging for static resources and health checks to reduce noise.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/swagger-ui")
                || path.startsWith("/api-docs")
                || path.startsWith("/v3/api-docs")
                || path.equals("/favicon.ico")
                || path.startsWith("/actuator");
    }
}

