package com.ms.user;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * O ms-user de verdade (Flyway, Hibernate com ddl-auto: validate, HTTP) contra um Postgres 17 do
 * Testcontainers. Sem Eureka e com o RabbitMQ apontando para uma porta fechada: a publicação
 * falha e é registrada em log, como no ADR-0001.
 */
final class AplicacaoComBanco implements AutoCloseable {

    static final String IMAGEM_POSTGRES = "postgres:17-alpine";

    private final PostgreSQLContainer banco;
    private ConfigurableApplicationContext contexto;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    AplicacaoComBanco() {
        this.banco = new PostgreSQLContainer(IMAGEM_POSTGRES).withDatabaseName("ms_user");
        banco.start();
    }

    /** Executa SQL no banco antes (ou depois) de a aplicação subir. */
    void executarSql(String sql) {
        try (Connection c = conexao(); var st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    void executarScript(String recurso) {
        try (var in = getClass().getResourceAsStream(recurso)) {
            executarSql(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Connection conexao() throws SQLException {
        return DriverManager.getConnection(banco.getJdbcUrl(), banco.getUsername(), banco.getPassword());
    }

    ConfigurableApplicationContext iniciar() {
        contexto = new SpringApplicationBuilder(UserApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + banco.getJdbcUrl(),
                "--spring.datasource.username=" + banco.getUsername(),
                "--spring.datasource.password=" + banco.getPassword(),
                "--spring.rabbitmq.addresses=amqp://app:app_dev@localhost:1",
                "--eureka.client.enabled=false");
        return contexto;
    }

    HttpResponse<String> get(String caminho) {
        return enviar(HttpRequest.newBuilder(uri(caminho)).GET().build());
    }

    HttpResponse<String> post(String caminho, String json) {
        return enviar(HttpRequest.newBuilder(uri(caminho))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    private URI uri(String caminho) {
        int porta = ((WebServerApplicationContext) contexto).getWebServer().getPort();
        return URI.create("http://localhost:" + porta + caminho);
    }

    private HttpResponse<String> enviar(HttpRequest requisicao) {
        try {
            return http.send(requisicao, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        if (contexto != null) {
            contexto.close();
        }
        banco.stop();
    }
}
