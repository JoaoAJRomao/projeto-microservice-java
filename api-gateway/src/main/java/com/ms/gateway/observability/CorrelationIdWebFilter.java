package com.ms.gateway.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Primeiro filtro do gateway: resolve o Correlation ID, envia-o ao serviço, devolve-o na resposta
 * (inclusive nos 503/504 gerados aqui) e registra um log de acesso estruturado por requisição.
 * Os serviços aceitam o ID que chega, então resposta, gateway e réplica usam o mesmo valor.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

    /** Atributos da troca lidos pelo {@code FallbackController}. */
    public static final String ATTR_CORRELATION_ID = CorrelationIdWebFilter.class.getName() + ".correlationId";
    public static final String ATTR_CAMINHO_ORIGINAL = CorrelationIdWebFilter.class.getName() + ".caminho";

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdWebFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = CorrelationId.resolve(exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER));
        String caminho = exchange.getRequest().getPath().value();

        ServerWebExchange comId = exchange.mutate()
                .request(r -> r.headers(h -> h.set(CorrelationId.HEADER, correlationId)))
                .build();
        comId.getAttributes().put(ATTR_CORRELATION_ID, correlationId);
        comId.getAttributes().put(ATTR_CAMINHO_ORIGINAL, caminho);
        comId.getResponse().beforeCommit(() -> {
            comId.getResponse().getHeaders().set(CorrelationId.HEADER, correlationId);
            return Mono.empty();
        });

        long inicio = System.nanoTime();
        return chain.filter(comId).doFinally(sinal -> {
            if (!isInfrastructurePath(caminho)) {
                long duracaoMs = (System.nanoTime() - inicio) / 1_000_000;
                HttpStatusCode status = comId.getResponse().getStatusCode();
                int codigo = status != null ? status.value() : 0;
                String metodo = exchange.getRequest().getMethod().name();
                log.atInfo()
                        .addKeyValue(CorrelationId.LOG_KEY, correlationId)
                        .addKeyValue("http.request.method", metodo)
                        .addKeyValue("url.path", caminho)
                        .addKeyValue("http.response.status_code", codigo)
                        .addKeyValue("event.duration_ms", duracaoMs)
                        .log("HTTP {} {} -> {} ({} ms)", metodo, caminho, codigo, duracaoMs);
            }
        });
    }

    /** Health checks e métricas são chamados a cada poucos segundos; não poluem o log. */
    private static boolean isInfrastructurePath(String caminho) {
        return caminho.startsWith("/health") || caminho.startsWith("/metrics") || caminho.startsWith("/info");
    }
}
