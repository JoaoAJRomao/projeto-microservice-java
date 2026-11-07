package com.ms.gateway;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.ms.gateway.observability.CorrelationIdWebFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@ExtendWith(OutputCaptureExtension.class)
class CorrelationIdGatewayTest extends GatewayTestBase {

    private static final String HEADER = "X-Correlation-ID";

    @Test
    void geraUuidQuandoAusenteRepassaEDevolve() {
        HttpResponse<String> resposta = get("/emails?page=0&size=20");

        String devolvido = resposta.headers().firstValue(HEADER).orElseThrow();
        assertThat(UUID.fromString(devolvido).toString()).isEqualTo(devolvido);
        assertThat(email.recebidas()).hasSize(1);
        assertThat(email.recebidas().getFirst().header(HEADER)).isEqualTo(devolvido);

        String outro = post("/user", "{\"name\":\"Ana\",\"email\":\"" + novoEmail() + "\"}").headers().firstValue(HEADER).orElseThrow();
        assertThat(outro).isNotEqualTo(devolvido);
        assertThat(UUID.fromString(outro).toString()).isEqualTo(outro);
    }

    @Test
    void validaFormatoDoHeaderRecebido() {
        String sessentaEQuatro = "a".repeat(64);
        Map<String, Boolean> mantido = Map.of(
                "apresentacao-0001", true,
                "abc", false,
                "a b", false,
                "a".repeat(65), false,
                sessentaEQuatro, true);

        mantido.forEach((enviado, deveManter) -> {
            email.reiniciar();
            HttpResponse<String> resposta = get("/emails?page=0&size=20", HEADER, enviado);

            String devolvido = resposta.headers().firstValue(HEADER).orElseThrow();
            String repassado = email.recebidas().getFirst().header(HEADER);
            assertThat(repassado).as("repassado para '%s'", enviado).isEqualTo(devolvido);
            if (deveManter) {
                assertThat(devolvido).as("'%s' deveria ser mantido", enviado).isEqualTo(enviado);
            } else {
                assertThat(devolvido).as("'%s' deveria ser substituído", enviado).isNotEqualTo(enviado);
                assertThat(UUID.fromString(devolvido).toString()).isEqualTo(devolvido);
            }
        });
    }

    @Test
    void registraUmaLinhaDeAcessoPorRequisicaoRoteada(CapturedOutput saida) throws InterruptedException {
        String idGet = "acesso-get-" + UUID.randomUUID().toString().substring(0, 8);
        String idPost = "acesso-post-" + UUID.randomUUID().toString().substring(0, 8);
        String idEmail = "acesso-email-" + UUID.randomUUID().toString().substring(0, 8);
        userA.responder(r -> ServidorFalso.Resposta.json(r.metodo().equals("POST") ? 201 : 200, "{}"));
        userB.responder(r -> ServidorFalso.Resposta.json(r.metodo().equals("POST") ? 201 : 200, "{}"));

        get("/user?page=0&size=20", HEADER, idGet);
        post("/user", "{\"name\":\"Ana\",\"email\":\"" + novoEmail() + "\"}", HEADER, idPost);
        get("/emails/user/3f2b6a3e-9a51-4d3c-8f0e-0c1f5b8a7d21", HEADER, idEmail);
        for (String operacional : List.of("/health", "/health/readiness", "/metrics", "/metrics/jvm.memory.used", "/info")) {
            get(operacional, HEADER, "operacional-0001");
        }

        List<String> ids = List.of(idGet, idPost, idEmail, "operacional-0001");
        await().untilAsserted(() -> assertThat(linhasDeAcesso(saida, ids)).hasSize(3));
        Thread.sleep(500);
        List<String> linhas = linhasDeAcesso(saida, ids);

        assertLinha(linhas, idGet, "GET", "/user", 200);
        assertLinha(linhas, idPost, "POST", "/user", 201);
        assertLinha(linhas, idEmail, "GET", "/emails/user/3f2b6a3e-9a51-4d3c-8f0e-0c1f5b8a7d21", 200);
        assertThat(linhas).noneMatch(l -> l.contains("operacional-0001"));
    }

    private static void assertLinha(List<String> linhas, String correlationId, String metodo, String caminho, int status) {
        List<String> doId = linhas.stream().filter(l -> correlationId.equals(campo(l, "correlationId"))).toList();
        assertThat(doId).as("linhas de %s", correlationId).hasSize(1);
        String linha = doId.getFirst();
        assertThat(campo(linha, "http.request.method")).isEqualTo(metodo);
        assertThat(campo(linha, "url.path")).isEqualTo(caminho);
        assertThat(((Number) campo(linha, "http.response.status_code")).intValue()).isEqualTo(status);
        assertThat(((Number) campo(linha, "event.duration_ms")).longValue()).isGreaterThanOrEqualTo(0);
    }

    /** Linhas de acesso das requisições deste teste (a de um teste anterior pode sair depois da resposta). */
    private static List<String> linhasDeAcesso(CapturedOutput saida, List<String> ids) {
        return saida.getOut().lines()
                .filter(l -> l.startsWith("{"))
                .filter(l -> CorrelationIdWebFilter.class.getName().equals(campo(l, "log.logger")))
                .filter(l -> ids.contains(String.valueOf(campo(l, "correlationId"))))
                .toList();
    }

    /** Campo ECS, aceitando tanto a chave com pontos quanto a forma aninhada. */
    private static Object campo(String json, String chave) {
        try {
            return JsonPath.read(json, "$['" + chave + "']");
        } catch (PathNotFoundException plano) {
            try {
                return JsonPath.read(json, "$." + chave);
            } catch (PathNotFoundException aninhado) {
                return null;
            }
        }
    }
}
