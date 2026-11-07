package com.ms.email.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Testes unitários puros (não sobem o contexto Spring nem dependem de banco/broker). */
class ObservabilityUtilsTest {

    @Test
    void reaproveitaCorrelationIdValido() {
        String recebido = "3f2b8c9e-1a2b-4c3d-9e8f-001122334455";
        assertEquals(recebido, CorrelationId.resolve(recebido));
    }

    @Test
    void geraNovoIdQuandoAusenteOuInseguro() {
        String gerado = CorrelationId.resolve(null);
        assertTrue(gerado.matches("^[0-9a-f-]{36}$"));

        String malicioso = "abc\n{\"log.level\":\"ERROR\"}";
        assertNotEquals(malicioso, CorrelationId.resolve(malicioso));
    }

    @Test
    void mascaraEmailNoLog() {
        assertEquals("m***@gmail.com", LogMasking.email("maria.silva@gmail.com"));
        assertEquals("***", LogMasking.email(null));
        assertEquals("***", LogMasking.email("sem-arroba"));
    }
}
