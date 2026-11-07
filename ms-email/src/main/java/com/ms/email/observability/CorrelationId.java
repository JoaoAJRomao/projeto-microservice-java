package com.ms.email.observability;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Utilitário do Correlation ID (ADR-0003).
 *
 * <p>No ms-email o ID chega no header AMQP {@value #HEADER} da mensagem publicada
 * pelo ms-user (ou no header HTTP de mesmo nome, nas consultas REST), vai para o MDC
 * (chave {@value #MDC_KEY}) e é gravado junto com o registro do e-mail.</p>
 *
 * <p>Classe duplicada de propósito nos dois serviços (sem biblioteca compartilhada) — ver ADR-0003.</p>
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";

    /** Aceita apenas valores "seguros" vindos de fora, evitando log injection. */
    private static final Pattern SAFE_VALUE = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    private CorrelationId() {
    }

    /** Reaproveita o ID recebido se for válido; caso contrário gera um UUID novo. */
    public static String resolve(String candidate) {
        if (candidate != null && SAFE_VALUE.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }

    /** ID da requisição corrente (ou um novo, se chamado fora de uma requisição). */
    public static String currentOrNew() {
        return resolve(MDC.get(MDC_KEY));
    }
}
