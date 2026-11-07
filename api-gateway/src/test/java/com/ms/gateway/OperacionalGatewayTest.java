package com.ms.gateway;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationContext;

import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OperacionalGatewayTest extends GatewayTestBase {

    @Autowired
    ApplicationContext contexto;

    @Test
    void endpointsOperacionaisSaoDoProprioGateway() {
        HttpResponse<String> health = get("/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(health.body(), "$.status")).isEqualTo("UP");

        for (String caminho : List.of("/health/liveness", "/health/readiness")) {
            assertThat(get(caminho).statusCode()).as(caminho).isEqualTo(200);
        }

        HttpResponse<String> info = get("/info");
        assertThat(info.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(info.body(), "$.app.name")).isEqualTo("api-gateway");

        HttpResponse<String> metrics = get("/metrics");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat((List<String>) JsonPath.read(metrics.body(), "$.names")).contains("jvm.memory.used");
        assertThat(get("/metrics/jvm.memory.used").statusCode()).isEqualTo(200);

        assertThat(userA.recebidas()).isEmpty();
        assertThat(userB.recebidas()).isEmpty();
        assertThat(email.recebidas()).isEmpty();
    }

    @Test
    void readinessRecusandoTrafegoResponde503() {
        try {
            AvailabilityChangeEvent.publish(contexto, ReadinessState.REFUSING_TRAFFIC);

            assertThat(get("/health/readiness").statusCode()).isEqualTo(503);
        } finally {
            AvailabilityChangeEvent.publish(contexto, ReadinessState.ACCEPTING_TRAFFIC);
        }
        assertThat(get("/health/readiness").statusCode()).isEqualTo(200);
    }
}
