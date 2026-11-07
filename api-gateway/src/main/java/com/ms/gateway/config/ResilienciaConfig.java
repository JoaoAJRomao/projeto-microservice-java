package com.ms.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Circuit breaker e timeout de cada rota (ADR-0005).
 *
 * <p>Conta como falha apenas exceção — conexão recusada, réplica inexistente ou timeout.
 * Respostas HTTP do serviço (409, 500...) chegam ao cliente como vieram e não abrem o circuito.</p>
 */
@Configuration
public class ResilienciaConfig {

    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> circuitBreakerPadrao(
            @Value("${gateway.resiliencia.timeout:5s}") Duration timeout,
            @Value("${gateway.resiliencia.janela:10}") int janela,
            @Value("${gateway.resiliencia.taxa-de-falha:50}") float taxaDeFalha,
            @Value("${gateway.resiliencia.tempo-aberto:10s}") Duration tempoAberto,
            @Value("${gateway.resiliencia.chamadas-meio-aberto:3}") int chamadasMeioAberto) {

        CircuitBreakerConfig circuitBreaker = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(janela)
                .minimumNumberOfCalls(janela)
                .failureRateThreshold(taxaDeFalha)
                .waitDurationInOpenState(tempoAberto)
                .permittedNumberOfCallsInHalfOpenState(chamadasMeioAberto)
                .build();
        TimeLimiterConfig timeLimiter = TimeLimiterConfig.custom().timeoutDuration(timeout).build();

        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(circuitBreaker)
                .timeLimiterConfig(timeLimiter)
                .build());
    }
}
