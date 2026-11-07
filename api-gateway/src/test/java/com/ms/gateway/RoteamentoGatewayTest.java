package com.ms.gateway;

import com.ms.gateway.ServidorFalso.Requisicao;
import com.ms.gateway.ServidorFalso.Resposta;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoteamentoGatewayTest extends GatewayTestBase {

    @Test
    void postUserRepassa201ComCorpoDoServico() {
        String corpoServico = "{\"userId\":\"3f2b6a3e-9a51-4d3c-8f0e-0c1f5b8a7d21\",\"name\":\"Maria Silva\",\"email\":\"maria.silva@exemplo.com\"}";
        userA.responder(Resposta.json(201, corpoServico));
        userB.responder(Resposta.json(201, corpoServico));
        String pedido = "{\"name\":\"Maria Silva\",\"email\":\"Maria.Silva@Exemplo.com\"}";

        HttpResponse<String> resposta = post("/user", pedido);

        assertThat(resposta.statusCode()).isEqualTo(201);
        assertThat(resposta.body()).isEqualTo(corpoServico);
        assertThat(resposta.headers().firstValue("Content-Type")).hasValue("application/json");
        List<Requisicao> chegaram = recebidasPorMsUser();
        assertThat(chegaram).hasSize(1);
        assertThat(chegaram.getFirst().metodo()).isEqualTo("POST");
        assertThat(chegaram.getFirst().caminho()).isEqualTo("/user");
        assertThat(chegaram.getFirst().corpo()).isEqualTo(pedido);
    }

    @Test
    void getsRepassam200ComMesmoCorpo() {
        String usuarios = "[{\"userId\":\"3f2b6a3e-9a51-4d3c-8f0e-0c1f5b8a7d21\",\"name\":\"Ana\",\"email\":\"ana@exemplo.com\"}]";
        String emails = "[{\"emailId\":\"a1\",\"statusEmail\":\"SENT\",\"correlationId\":\"apresentacao-0001\"}]";
        String emailsDoUsuario = "[{\"emailId\":\"b2\",\"userId\":\"3f2b6a3e-9a51-4d3c-8f0e-0c1f5b8a7d21\"}]";
        userA.responder(Resposta.json(200, usuarios));
        userB.responder(Resposta.json(200, usuarios));
        email.responder(r -> r.caminho().startsWith("/emails/user/")
                ? Resposta.json(200, emailsDoUsuario)
                : Resposta.json(200, emails));

        Map<String, String> esperado = Map.of(
                "/user?page=0&size=20", usuarios,
                "/emails?page=0&size=20", emails,
                "/emails/user/3f2b6a3e-9a51-4d3c-8f0e-0c1f5b8a7d21", emailsDoUsuario);

        esperado.forEach((caminho, corpo) -> {
            HttpResponse<String> resposta = get(caminho);
            assertThat(resposta.statusCode()).as(caminho).isEqualTo(200);
            assertThat(resposta.body()).as(caminho).isEqualTo(corpo);
            assertThat(resposta.headers().firstValue("Content-Type")).as(caminho).hasValue("application/json");
        });

        assertThat(recebidasPorMsUser()).extracting(Requisicao::caminho).containsExactly("/user?page=0&size=20");
        assertThat(email.recebidas()).extracting(Requisicao::caminho).containsExactlyInAnyOrder(
                "/emails?page=0&size=20", "/emails/user/3f2b6a3e-9a51-4d3c-8f0e-0c1f5b8a7d21");
    }

    @Test
    void respostas4xxPassamSemAlteracao() {
        String duplicado = "{\"detail\":\"Já existe um usuário cadastrado com este e-mail.\",\"instance\":\"/user\",\"status\":409,\"title\":\"E-mail já cadastrado\",\"correlationId\":\"apresentacao-0001\"}";
        String validacao = "{\"detail\":\"Invalid request content.\",\"instance\":\"/user\",\"status\":400,\"title\":\"Bad Request\"}";
        String uuidInvalido = "{\"detail\":\"Failed to convert 'userId' with value: 'abc'\",\"instance\":\"/emails/user/abc\",\"status\":400,\"title\":\"Bad Request\"}";
        userA.responder(r -> r.corpo().contains("nao-e-email") ? Resposta.problema(400, validacao) : Resposta.problema(409, duplicado));
        userB.responder(r -> r.corpo().contains("nao-e-email") ? Resposta.problema(400, validacao) : Resposta.problema(409, duplicado));
        email.responder(Resposta.problema(400, uuidInvalido));

        HttpResponse<String> conflito = post("/user", "{\"name\":\"Maria\",\"email\":\"maria@exemplo.com\"}");
        assertThat(conflito.statusCode()).isEqualTo(409);
        assertThat(conflito.body()).isEqualTo(duplicado).contains("\"title\":\"E-mail já cadastrado\"").contains("\"correlationId\"");
        assertThat(conflito.headers().firstValue("Content-Type")).hasValue("application/problem+json");

        HttpResponse<String> invalido = post("/user", "{\"name\":\"Maria\",\"email\":\"nao-e-email\"}");
        assertThat(invalido.statusCode()).isEqualTo(400);
        assertThat(invalido.body()).isEqualTo(validacao);
        assertThat(invalido.headers().firstValue("Content-Type")).hasValue("application/problem+json");

        HttpResponse<String> abc = get("/emails/user/abc");
        assertThat(abc.statusCode()).isEqualTo(400);
        assertThat(abc.body()).isEqualTo(uuidInvalido);
        assertThat(abc.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    }

    @Test
    void caminhoSemRotaResponde404SemChamarServico() {
        for (String caminho : List.of("/users", "/admin", "/emailsx")) {
            assertThat(get(caminho).statusCode()).as(caminho).isEqualTo(404);
        }
        assertThat(post("/users", "{}").statusCode()).isEqualTo(404);

        assertThat(recebidasPorMsUser()).isEmpty();
        assertThat(email.recebidas()).isEmpty();
    }

    @Test
    void distribuiEmRoundRobinEntreDuasInstancias() {
        for (int i = 0; i < 20; i++) {
            assertThat(get("/user?page=0&size=20").statusCode()).isEqualTo(200);
        }

        assertThat(userA.recebidas()).hasSizeBetween(9, 11);
        assertThat(userB.recebidas()).hasSizeBetween(9, 11);
        assertThat(userA.recebidas().size() + userB.recebidas().size()).isEqualTo(20);
    }

    private static List<Requisicao> recebidasPorMsUser() {
        return java.util.stream.Stream.concat(userA.recebidas().stream(), userB.recebidas().stream()).toList();
    }
}
