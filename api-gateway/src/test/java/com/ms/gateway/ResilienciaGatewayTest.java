package com.ms.gateway;

import com.jayway.jsonpath.JsonPath;
import com.ms.gateway.ServidorFalso.Resposta;
import com.ms.gateway.config.RotasConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ResilienciaGatewayTest extends GatewayTestBase {

    private static final String USUARIO = "3f2b6a3e-9a51-4d3c-8f0e-0c1f5b8a7d21";

    @Test
    void getRepeteUmaVezEmOutraInstancia() {
        instanciaRecusandoConexao(RotasConfig.MS_USER, userB);
        for (int i = 0; i < 6; i++) {
            assertThat(get("/user").statusCode()).as("conexão recusada, GET %d", i).isEqualTo(200);
        }
        assertThat(userB.recebidas()).hasSize(6);

        for (int status : new int[]{502, 503}) {
            estadoInicial();
            userA.responder(Resposta.json(status, "{}"));
            for (int i = 0; i < 6; i++) {
                HttpResponse<String> resposta = get("/user");
                assertThat(resposta.statusCode()).as("%d na primeira réplica, GET %d", status, i).isEqualTo(200);
                assertThat(resposta.body()).contains("user-b");
            }
            assertThat(userB.recebidas()).as("%d", status).hasSize(6);
            assertThat(userA.recebidas()).as("%d", status).isNotEmpty();
        }

        for (int status : new int[]{502, 503}) {
            estadoInicial();
            userA.responder(Resposta.json(status, "{\"de\":\"a\"}"));
            userB.responder(Resposta.json(status, "{\"de\":\"b\"}"));

            HttpResponse<String> resposta = get("/user");

            assertThat(resposta.statusCode()).isEqualTo(status);
            assertThat(userA.recebidas().size() + userB.recebidas().size()).as("tentativas com %d nas duas", status).isEqualTo(2);
            assertThat(userA.recebidas()).hasSize(1);
            assertThat(userB.recebidas()).hasSize(1);
        }
    }

    @Test
    void postNuncaRepete() {
        userA.responder(Resposta.json(502, "{}"));
        userB.responder(Resposta.json(502, "{}"));
        assertThat(post("/user", corpoCadastro()).statusCode()).isEqualTo(502);
        assertThat(userA.recebidas().size() + userB.recebidas().size()).as("502").isEqualTo(1);

        estadoInicial();
        userA.responder(Resposta.fecharConexao());
        userB.responder(Resposta.fecharConexao());
        assertThat(post("/user", corpoCadastro()).statusCode()).isEqualTo(503);
        assertThat(userA.recebidas().size() + userB.recebidas().size()).as("conexão fechada").isEqualTo(1);

        estadoInicial();
        instanciaRecusandoConexao(RotasConfig.MS_USER, userB);
        userB.responder(Resposta.json(201, "{}"));
        List<Integer> status = List.of(post("/user", corpoCadastro()).statusCode(), post("/user", corpoCadastro()).statusCode());
        assertThat(status).containsExactlyInAnyOrder(201, 503);
        assertThat(userB.recebidas()).as("conexão recusada").hasSize(1);
    }

    @Test
    void timeoutDe5sResponde504() {
        Map<String, Supplier<HttpResponse<String>>> rotas = Map.of(
                "POST /user", () -> post("/user", corpoCadastro()),
                "GET /user", () -> get("/user"),
                "GET /emails", () -> get("/emails"),
                "GET /emails/user/{userId}", () -> get("/emails/user/" + USUARIO));

        for (ServidorFalso s : List.of(userA, userB, email)) {
            s.responder(Resposta.json(200, "{}").depoisDe(Duration.ofSeconds(6)));
        }
        Map<String, CompletableFuture<Medida>> lentos = disparar(rotas);
        lentos.forEach((rota, futuro) -> {
            Medida m = futuro.join();
            String servico = rota.contains("emails") ? "ms-email" : "ms-user";
            assertThat(m.resposta().statusCode()).as(rota).isEqualTo(504);
            assertThat(m.duracao()).as(rota).isLessThanOrEqualTo(Duration.ofSeconds(6));
            assertThat(m.resposta().headers().firstValue("Content-Type")).as(rota).hasValue("application/problem+json");
            assertThat((String) JsonPath.read(m.resposta().body(), "$.title")).as(rota).isEqualTo("Tempo de resposta esgotado");
            assertThat((Integer) JsonPath.read(m.resposta().body(), "$.status")).as(rota).isEqualTo(504);
            assertThat((String) JsonPath.read(m.resposta().body(), "$.detail")).as(rota).isEqualTo(servico + " não respondeu em 5 s.");
        });

        for (ServidorFalso s : List.of(userA, userB, email)) {
            s.responder(Resposta.json(200, "{}").depoisDe(Duration.ofSeconds(4)));
        }
        disparar(rotas).forEach((rota, futuro) ->
                assertThat(futuro.join().resposta().statusCode()).as(rota).isEqualTo(200));
    }

    @Test
    void circuitoAbertoResponde503SemChamarServico() throws InterruptedException {
        instancias(RotasConfig.MS_USER, userA);
        userA.responder(Resposta.json(201, "{}"));
        for (int i = 0; i < 5; i++) {
            assertThat(post("/user", corpoCadastro()).statusCode()).isEqualTo(201);
        }
        userA.responder(Resposta.fecharConexao());
        for (int i = 0; i < 4; i++) {
            assertThat(post("/user", corpoCadastro()).statusCode()).isEqualTo(503);
        }
        assertThat(userA.recebidas()).as("9 chamadas, 44%% de falha: circuito ainda fechado").hasSize(9);
        assertThat(post("/user", corpoCadastro()).statusCode()).isEqualTo(503);
        assertThat(userA.recebidas()).as("a 10ª chamada ainda chega ao serviço").hasSize(10);
        assertThat(circuito().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        userA.responder(Resposta.json(201, "{}"));
        for (int i = 0; i < 5; i++) {
            assertRejeitadaRapido(() -> post("/user", corpoCadastro()));
            assertRejeitadaRapido(() -> get("/user"));
        }
        assertThat(userA.recebidas()).hasSize(10);

        Thread.sleep(8_500);
        assertRejeitadaRapido(() -> post("/user", corpoCadastro()));
        assertThat(userA.recebidas()).as("ainda aberto perto dos 10 s").hasSize(10);
    }

    @Test
    void circuitoMeioAbertoLibera3Chamadas() throws InterruptedException {
        instancias(RotasConfig.MS_USER, userA);
        userA.responder(Resposta.fecharConexao());
        for (int i = 0; i < 10; i++) {
            post("/user", corpoCadastro());
        }
        assertThat(circuito().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int antes = userA.recebidas().size();

        Thread.sleep(10_500);
        List<Integer> status = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            status.add(post("/user", corpoCadastro()).statusCode());
        }

        assertThat(userA.recebidas().size() - antes).as("chamadas de teste no meio-aberto").isEqualTo(3);
        assertThat(status).containsOnly(503);
        assertThat(circuito().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void respostas4xxE500NaoAbremCircuito() {
        instancias(RotasConfig.MS_USER, userA);
        userA.responder(Resposta.problema(409, "{\"title\":\"E-mail já cadastrado\",\"status\":409}"));
        for (int i = 0; i < 20; i++) {
            assertThat(post("/user", corpoCadastro()).statusCode()).isEqualTo(409);
        }
        assertThat(circuito().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(post("/user", corpoCadastro()).statusCode()).isEqualTo(409);
        assertThat(userA.recebidas()).hasSize(21);

        userA.responder(Resposta.problema(500, "{\"title\":\"Internal Server Error\",\"status\":500}"));
        for (int i = 0; i < 12; i++) {
            HttpResponse<String> resposta = post("/user", corpoCadastro());
            assertThat(resposta.statusCode()).isEqualTo(500);
            assertThat(resposta.body()).isEqualTo("{\"title\":\"Internal Server Error\",\"status\":500}");
        }
        assertThat(get("/user").statusCode()).isEqualTo(500);
        assertThat(userA.recebidas()).as("GET com 500 não é repetido").hasSize(21 + 12 + 1);
        assertThat(circuito().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void muitasChamadasSimultaneasNaoAbremCircuito() {
        int simultaneas = 60;
        userA.responder(Resposta.json(201, "{}").depoisDe(Duration.ofMillis(500)));
        userB.responder(Resposta.json(201, "{}").depoisDe(Duration.ofMillis(500)));

        ExecutorService clientes = Executors.newFixedThreadPool(simultaneas);
        try {
            List<CompletableFuture<Integer>> respostas = new ArrayList<>();
            for (int i = 0; i < simultaneas; i++) {
                respostas.add(CompletableFuture.supplyAsync(() -> post("/user", corpoCadastro()).statusCode(), clientes));
            }
            assertThat(respostas.stream().map(CompletableFuture::join).toList())
                    .as("%d POSTs em paralelo, cada um levando 500 ms", simultaneas)
                    .hasSize(simultaneas)
                    .containsOnly(201);
        } finally {
            clientes.shutdownNow();
        }
        assertThat(userA.recebidas().size() + userB.recebidas().size()).isEqualTo(simultaneas);
        assertThat(circuito().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private record Medida(HttpResponse<String> resposta, Duration duracao) {
    }

    private static Map<String, CompletableFuture<Medida>> disparar(Map<String, Supplier<HttpResponse<String>>> rotas) {
        Map<String, CompletableFuture<Medida>> futuros = new java.util.HashMap<>();
        rotas.forEach((rota, chamada) -> futuros.put(rota, CompletableFuture.supplyAsync(() -> {
            long inicio = System.nanoTime();
            HttpResponse<String> resposta = chamada.get();
            return new Medida(resposta, Duration.ofNanos(System.nanoTime() - inicio));
        })));
        return futuros;
    }

    private void assertRejeitadaRapido(Supplier<HttpResponse<String>> chamada) {
        long inicio = System.nanoTime();
        HttpResponse<String> resposta = chamada.get();
        Duration duracao = Duration.ofNanos(System.nanoTime() - inicio);
        assertThat(resposta.statusCode()).isEqualTo(503);
        assertThat((String) JsonPath.read(resposta.body(), "$.title")).isEqualTo("Serviço indisponível");
        assertThat(duracao).isLessThan(Duration.ofMillis(100));
    }

    private CircuitBreaker circuito() {
        return circuitBreakers.getCircuitBreakerRegistry().circuitBreaker(RotasConfig.MS_USER);
    }

    private static String corpoCadastro() {
        return "{\"name\":\"Ana\",\"email\":\"" + novoEmail() + "\"}";
    }
}
