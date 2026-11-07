package com.ms.gateway.config;

import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory.RetryConfig;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.io.IOException;

/**
 * Rotas públicas (mesmos caminhos que os serviços expõem) e balanceamento via Eureka ({@code lb://}).
 *
 * <p>A ordem dos filtros importa: o circuit breaker (com o timeout) envolve o retry, então
 * o limite de 5 s vale para a requisição inteira, tentativas incluídas — ver ADR-0005.</p>
 */
@Configuration
public class RotasConfig {

    public static final String MS_USER = "ms-user";
    public static final String MS_EMAIL = "ms-email";

    @Bean
    public RouteLocator rotas(RouteLocatorBuilder builder) {
        return builder.routes()
                .route(MS_USER, r -> r.path("/user", "/user/**")
                        .filters(f -> resiliencia(f, MS_USER))
                        .uri("lb://" + MS_USER))
                .route(MS_EMAIL, r -> r.path("/emails", "/emails/**")
                        .filters(f -> resiliencia(f, MS_EMAIL))
                        .uri("lb://" + MS_EMAIL))
                .build();
    }

    private static GatewayFilterSpec resiliencia(GatewayFilterSpec f, String servico) {
        return f.circuitBreaker(c -> c.setName(servico).setFallbackUri("forward:/fallback/" + servico))
                .retry(RotasConfig::retryDeLeitura);
    }

    /**
     * Só GET é repetido, uma vez, e só quando a réplica não atendeu: falha de conexão, 502 ou 503.
     * POST nunca é repetido (um segundo cadastro devolveria 409 ao cliente). Sem {@code series}:
     * o padrão do Spring Cloud Gateway repetiria qualquer 5xx, inclusive um 500 de regra de negócio.
     */
    private static void retryDeLeitura(RetryConfig retry) {
        retry.setRetries(1)
                .setMethods(HttpMethod.GET)
                .setSeries()
                .setStatuses(HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE)
                .setExceptions(IOException.class)
                .setBackoff(null);
    }
}
