package com.ms.user;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigracaoTest {

    private record Historico(String versao, String tipo, String script, boolean sucesso) {
    }

    private record Usuario(String id, String nome, String email) {
    }

    @Test
    void bancoVazioRecebeV1() throws SQLException {
        try (AplicacaoComBanco app = new AplicacaoComBanco()) {
            ConfigurableApplicationContext contexto = app.iniciar();

            assertThat(contexto.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            assertThat(historico(app)).containsExactly(new Historico("1", "SQL", "V1__esquema_inicial.sql", true));

            HttpResponse<String> cadastro = app.post("/user", "{\"name\":\"Maria Silva\",\"email\":\"Maria@Exemplo.com\"}");
            assertThat(cadastro.statusCode()).isEqualTo(201);
            assertThat((String) JsonPath.read(cadastro.body(), "$.email")).isEqualTo("maria@exemplo.com");
            assertThat(usuarios(app)).extracting(Usuario::email).containsExactly("maria@exemplo.com");
        }
    }

    @Test
    void esquemaLegadoRecebeBaselineSemPerderDados() throws SQLException {
        try (AplicacaoComBanco app = new AplicacaoComBanco()) {
            app.executarScript("/legado/esquema-hibernate.sql");
            app.executarSql("""
                    INSERT INTO users (user_id, email, name) VALUES
                      ('11111111-1111-1111-1111-111111111111', 'ana@exemplo.com', 'Ana'),
                      ('22222222-2222-2222-2222-222222222222', 'bruno@exemplo.com', 'Bruno'),
                      ('33333333-3333-3333-3333-333333333333', 'carla@exemplo.com', 'Carla');
                    """);
            List<Usuario> antes = usuarios(app);

            app.iniciar();

            List<Historico> historico = historico(app);
            assertThat(historico).hasSize(1);
            assertThat(historico.getFirst().versao()).isEqualTo("1");
            assertThat(historico.getFirst().tipo()).isEqualTo("BASELINE");
            assertThat(historico.getFirst().sucesso()).isTrue();
            assertThat(usuarios(app)).containsExactlyElementsOf(antes).hasSize(3);

            HttpResponse<String> lista = app.get("/user?page=0&size=20");
            assertThat(lista.statusCode()).isEqualTo(200);
            assertThat((List<String>) JsonPath.read(lista.body(), "$[*].userId")).containsExactlyInAnyOrder(
                    "11111111-1111-1111-1111-111111111111",
                    "22222222-2222-2222-2222-222222222222",
                    "33333333-3333-3333-3333-333333333333");

            assertThat(app.post("/user", "{\"name\":\"Diego\",\"email\":\"diego@exemplo.com\"}").statusCode()).isEqualTo(201);
            assertThat(app.post("/user", "{\"name\":\"Ana 2\",\"email\":\"ana@exemplo.com\"}").statusCode()).isEqualTo(409);
            assertThat(usuarios(app)).hasSize(4).containsAll(antes);
        }
    }

    private static List<Historico> historico(AplicacaoComBanco app) throws SQLException {
        try (Connection c = app.conexao();
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT version, type, script, success FROM flyway_schema_history ORDER BY installed_rank")) {
            List<Historico> linhas = new ArrayList<>();
            while (rs.next()) {
                linhas.add(new Historico(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)));
            }
            return linhas;
        }
    }

    private static List<Usuario> usuarios(AplicacaoComBanco app) throws SQLException {
        try (Connection c = app.conexao();
             ResultSet rs = c.createStatement().executeQuery("SELECT user_id, name, email FROM users ORDER BY email")) {
            List<Usuario> linhas = new ArrayList<>();
            while (rs.next()) {
                linhas.add(new Usuario(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
            return linhas;
        }
    }
}
