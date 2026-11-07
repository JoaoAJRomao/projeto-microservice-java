package com.ms.email;

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
 * O ms-email de verdade (Flyway, Hibernate com ddl-auto: validate, HTTP) contra um Postgres 17 do
 * Testcontainers. Sem Eureka, sem consumidor do RabbitMQ e com SMTP numa porta fechada: um envio
 * vira status ERROR no histórico, como em produção quando o SMTP cai.
 */
final class AplicacaoComBanco implements AutoCloseable {

    static final String IMAGEM_POSTGRES = "postgres:17-alpine";

    private final PostgreSQLContainer banco;
    private ConfigurableApplicationContext contexto;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    AplicacaoComBanco() {
        this.banco = new PostgreSQLContainer(IMAGEM_POSTGRES).withDatabaseName("ms_email");
        banco.start();
    }

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
        contexto = new SpringApplicationBuilder(EmailApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + banco.getJdbcUrl(),
                "--spring.datasource.username=" + banco.getUsername(),
                "--spring.datasource.password=" + banco.getPassword(),
                "--spring.rabbitmq.addresses=amqp://app:app_dev@localhost:1",
                "--spring.rabbitmq.listener.simple.auto-startup=false",
                "--spring.mail.host=localhost",
                "--spring.mail.port=1",
                "--eureka.client.enabled=false");
        return contexto;
    }

    HttpResponse<String> get(String caminho) {
        int porta = ((WebServerApplicationContext) contexto).getWebServer().getPort();
        try {
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
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
