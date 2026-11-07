package com.ms.gateway.fallback;

import com.ms.gateway.observability.CorrelationIdWebFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR;

/**
 * Resposta do gateway quando a rota não consegue uma resposta do serviço (RFC 9457, como nos serviços):
 * 504 se o serviço não respondeu no prazo; 503 para réplica inexistente, conexão recusada ou circuito aberto.
 */
@RestController
public class FallbackController {

    /** O Spring 7 omite o {@code type} padrão; o contrato do gateway (ADR-0005) o traz explícito. */
    private static final URI SEM_TIPO = URI.create("about:blank");

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    private final Duration timeout;

    public FallbackController(@Value("${gateway.resiliencia.timeout:5s}") Duration timeout) {
        this.timeout = timeout;
    }

    @RequestMapping("/fallback/{servico}")
    public ResponseEntity<ProblemDetail> fallback(@PathVariable("servico") String servico, ServerWebExchange exchange) {
        Throwable causa = exchange.getAttribute(CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        if (causa == null) {
            // Chamado direto pelo cliente, e não pelo circuit breaker: não é uma rota pública.
            return ResponseEntity.notFound().build();
        }
        Object correlationId = exchange.getAttribute(CorrelationIdWebFilter.ATTR_CORRELATION_ID);
        log.atWarn()
                .addKeyValue("correlationId", correlationId)
                .log("Sem resposta de {}: {}: {}", servico, causa.getClass().getName(), causa.getMessage());

        ProblemDetail problema;
        if (causa instanceof TimeoutException) {
            problema = ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT,
                    servico + " não respondeu em " + timeout.toSeconds() + " s.");
            problema.setTitle("Tempo de resposta esgotado");
        } else {
            problema = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    "Nenhuma instância de " + servico + " disponível no momento.");
            problema.setTitle("Serviço indisponível");
        }
        problema.setType(SEM_TIPO);
        String caminho = exchange.getAttribute(CorrelationIdWebFilter.ATTR_CAMINHO_ORIGINAL);
        problema.setInstance(URI.create(caminho != null ? caminho : "/"));
        problema.setProperty("correlationId", correlationId);

        return ResponseEntity.status(problema.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problema);
    }
}
