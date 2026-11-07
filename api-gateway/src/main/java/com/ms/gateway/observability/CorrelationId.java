package com.ms.gateway.observability;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Utilitário do Correlation ID (ADR-0003), com a mesma regra dos serviços.
 *
 * <p>Classe duplicada de propósito em cada módulo (sem biblioteca compartilhada) — ver ADR-0003.</p>
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-ID";
    public static final String LOG_KEY = "correlationId";

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
}
