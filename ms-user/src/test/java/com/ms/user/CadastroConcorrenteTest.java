package com.ms.user;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CadastroConcorrenteTest {

    private static final int RODADAS = 20;

    @Test
    void mesmoEmailSimultaneoGeraUm201EUm409() throws SQLException {
        try (AplicacaoComBanco app = new AplicacaoComBanco();
             ExecutorService threads = Executors.newFixedThreadPool(2)) {
            app.iniciar();

            for (int rodada = 0; rodada < RODADAS; rodada++) {
                String json = "{\"name\":\"Concorrente\",\"email\":\"concorrente" + rodada + "@exemplo.com\"}";
                CyclicBarrier largada = new CyclicBarrier(2);
                List<CompletableFuture<HttpResponse<String>>> respostas = List.of(
                        CompletableFuture.supplyAsync(() -> aguardarEPostar(app, largada, json), threads),
                        CompletableFuture.supplyAsync(() -> aguardarEPostar(app, largada, json), threads));

                List<Integer> status = respostas.stream().map(f -> f.join().statusCode()).toList();
                assertThat(status).as("rodada %d", rodada).containsExactlyInAnyOrder(201, 409);
                HttpResponse<String> conflito = respostas.stream().map(CompletableFuture::join)
                        .filter(r -> r.statusCode() == 409).findFirst().orElseThrow();
                assertThat(conflito.body()).contains("\"title\":\"E-mail já cadastrado\"");
            }

            try (Connection c = app.conexao()) {
                assertThatThrownBy(() -> c.createStatement().execute(
                        "INSERT INTO users (user_id, email, name) VALUES (gen_random_uuid(), 'concorrente0@exemplo.com', 'Duplicado')"))
                        .as("a V1 mantém a constraint UNIQUE de users.email")
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("uk6dotkott2kjsp8vw4d0m25fb7");
            }
        }
    }

    private static HttpResponse<String> aguardarEPostar(AplicacaoComBanco app, CyclicBarrier largada, String json) {
        try {
            largada.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return app.post("/user", json);
    }
}
