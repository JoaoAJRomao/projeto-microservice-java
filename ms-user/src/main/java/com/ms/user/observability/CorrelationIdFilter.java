package com.ms.user.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Primeiro filtro da cadeia HTTP: garante um Correlation ID por requisição,
 * coloca-o no MDC (aparece em todos os logs) e devolve-o no header da resposta.
 * Também registra um log de acesso estruturado (método, caminho, status, duração).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String correlationId = CorrelationId.resolve(request.getHeader(CorrelationId.HEADER));
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);

        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            if (!isInfrastructurePath(request.getRequestURI())) {
                long durationMs = (System.nanoTime() - start) / 1_000_000;
                log.atInfo()
                        .addKeyValue("http.request.method", request.getMethod())
                        .addKeyValue("url.path", request.getRequestURI())
                        .addKeyValue("http.response.status_code", response.getStatus())
                        .addKeyValue("event.duration_ms", durationMs)
                        .log("HTTP {} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(),
                                response.getStatus(), durationMs);
            }
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    /** Health checks e métricas são chamados a cada poucos segundos; não poluem o log. */
    private boolean isInfrastructurePath(String uri) {
        return uri.startsWith("/health") || uri.startsWith("/metrics") || uri.startsWith("/info");
    }
}
