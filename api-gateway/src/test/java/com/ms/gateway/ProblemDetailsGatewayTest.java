package com.ms.gateway;

import com.jayway.jsonpath.JsonPath;
import com.ms.gateway.config.RotasConfig;
import com.ms.gateway.ServidorFalso.Resposta;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemDetailsGatewayTest extends GatewayTestBase {

    private static final String USUARIO = "3f2b6a3e-9a51-4d3c-8f0e-0c1f5b8a7d21";

    @Test
    void semInstanciaResponde503() {
        instancias(RotasConfig.MS_USER);
        instancias(RotasConfig.MS_EMAIL);

        Map<String, Supplier<HttpResponse<String>>> rotas = Map.of(
                "/user|ms-user|POST", () -> post("/user", "{\"name\":\"Ana\",\"email\":\"" + novoEmail() + "\"}"),
                "/user|ms-user|GET", () -> get("/user"),
                "/emails|ms-email|GET", () -> get("/emails"),
                "/emails/user/" + USUARIO + "|ms-email|GET", () -> get("/emails/user/" + USUARIO));

        rotas.forEach((chave, chamada) -> {
            String[] partes = chave.split("\\|");
            String caminho = partes[0];
            String servico = partes[1];
            HttpResponse<String> resposta = chamada.get();

            assertThat(resposta.statusCode()).as(chave).isEqualTo(503);
            assertThat(resposta.headers().firstValue("Content-Type")).as(chave).hasValue("application/problem+json");
            assertThat((String) JsonPath.read(resposta.body(), "$.type")).as(chave).isEqualTo("about:blank");
            assertThat((String) JsonPath.read(resposta.body(), "$.title")).as(chave).isEqualTo("Serviço indisponível");
            assertThat((Integer) JsonPath.read(resposta.body(), "$.status")).as(chave).isEqualTo(503);
            assertThat((String) JsonPath.read(resposta.body(), "$.detail")).as(chave)
                    .isEqualTo("Nenhuma instância de " + servico + " disponível no momento.");
            assertThat((String) JsonPath.read(resposta.body(), "$.instance")).as(chave).isEqualTo(caminho);
        });
    }

    @Test
    void semInstanciaDeUmServicoNaoAfetaOOutro() {
        instancias(RotasConfig.MS_USER);

        assertThat(post("/user", "{\"name\":\"Ana\",\"email\":\"" + novoEmail() + "\"}").statusCode()).isEqualTo(503);
        assertThat(get("/emails").statusCode()).isEqualTo(200);
    }

    @Test
    void correlationIdDoCorpoIgualAoHeader() {
        instancias(RotasConfig.MS_USER);
        email.responder(Resposta.json(200, "[]").depoisDe(Duration.ofSeconds(6)));

        HttpResponse<String> semInstanciaGerado = get("/user");
        HttpResponse<String> semInstanciaRecebido = get("/user", "X-Correlation-ID", "apresentacao-0503");
        HttpResponse<String> timeout = get("/emails", "X-Correlation-ID", "apresentacao-0504");

        assertThat(semInstanciaGerado.statusCode()).isEqualTo(503);
        assertThat(semInstanciaRecebido.statusCode()).isEqualTo(503);
        assertThat(timeout.statusCode()).isEqualTo(504);
        for (HttpResponse<String> resposta : new HttpResponse[]{semInstanciaGerado, semInstanciaRecebido, timeout}) {
            String header = resposta.headers().firstValue("X-Correlation-ID").orElseThrow();
            assertThat((String) JsonPath.read(resposta.body(), "$.correlationId")).isEqualTo(header);
        }
        assertThat(semInstanciaRecebido.headers().firstValue("X-Correlation-ID")).hasValue("apresentacao-0503");
        assertThat(timeout.headers().firstValue("X-Correlation-ID")).hasValue("apresentacao-0504");
    }
}
