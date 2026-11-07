package com.ms.email;

import com.jayway.jsonpath.JsonPath;
import com.ms.email.enums.StatusEmail;
import com.ms.email.models.EmailModel;
import com.ms.email.services.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigracaoTest {

    private record Historico(String versao, String tipo, String script, boolean sucesso) {
    }

    @Test
    void bancoVazioRecebeV1() throws SQLException {
        try (AplicacaoComBanco app = new AplicacaoComBanco()) {
            ConfigurableApplicationContext contexto = app.iniciar();

            assertThat(contexto.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            assertThat(historico(app)).containsExactly(new Historico("1", "SQL", "V1__esquema_inicial.sql", true));

            UUID usuario = UUID.randomUUID();
            EmailModel salvo = contexto.getBean(EmailService.class).sendEmail(novoEmail(usuario, "apresentacao-0001"));
            assertThat(salvo.getStatusEmail()).isEqualTo(StatusEmail.ERROR);
            HttpResponse<String> lista = app.get("/emails/user/" + usuario);
            assertThat(lista.statusCode()).isEqualTo(200);
            assertThat((List<String>) JsonPath.read(lista.body(), "$[*].correlationId")).containsExactly("apresentacao-0001");
        }
    }

    @Test
    void esquemaLegadoRecebeBaselineSemPerderDados() throws SQLException {
        try (AplicacaoComBanco app = new AplicacaoComBanco()) {
            app.executarScript("/legado/esquema-hibernate.sql");
            app.executarSql("""
                    INSERT INTO emails (email_id, correlation_id, email_from, email_to, send_date_email, status_email, subject, text, user_id) VALUES
                      ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'legado-0001', 'no-reply@cadastro.local', 'ana@exemplo.com',
                       '2026-10-30 21:00:00', 0, 'Cadastro realizado com sucesso!', 'Ana, seja bem-vindo(a)!', '11111111-1111-1111-1111-111111111111'),
                      ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'legado-0002', 'no-reply@cadastro.local', 'bruno@exemplo.com',
                       '2026-10-30 21:05:00', 1, 'Cadastro realizado com sucesso!', 'Bruno, seja bem-vindo(a)!', '22222222-2222-2222-2222-222222222222');
                    """);
            List<List<String>> antes = linhas(app);

            ConfigurableApplicationContext contexto = app.iniciar();

            List<Historico> historico = historico(app);
            assertThat(historico).hasSize(1);
            assertThat(historico.getFirst().versao()).isEqualTo("1");
            assertThat(historico.getFirst().tipo()).isEqualTo("BASELINE");
            assertThat(historico.getFirst().sucesso()).isTrue();
            assertThat(linhas(app)).containsExactlyElementsOf(antes).hasSize(2);

            HttpResponse<String> doAna = app.get("/emails/user/11111111-1111-1111-1111-111111111111");
            assertThat(doAna.statusCode()).isEqualTo(200);
            assertThat((List<String>) JsonPath.read(doAna.body(), "$[*].statusEmail")).containsExactly("SENT");

            UUID novo = UUID.randomUUID();
            contexto.getBean(EmailService.class).sendEmail(novoEmail(novo, "depois-0001"));
            assertThat(linhas(app)).hasSize(3).containsAll(antes);
            assertThat((List<String>) JsonPath.read(app.get("/emails/user/" + novo).body(), "$[*].correlationId"))
                    .containsExactly("depois-0001");
        }
    }

    private static EmailModel novoEmail(UUID usuario, String correlationId) {
        EmailModel email = new EmailModel();
        email.setUserId(usuario);
        email.setEmailTo("pessoa@exemplo.com");
        email.setSubject("Cadastro realizado com sucesso!");
        email.setText("Seja bem-vindo(a)!");
        email.setCorrelationId(correlationId);
        return email;
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

    private static List<List<String>> linhas(AplicacaoComBanco app) throws SQLException {
        try (Connection c = app.conexao();
             ResultSet rs = c.createStatement().executeQuery("""
                     SELECT email_id, correlation_id, email_from, email_to, send_date_email, status_email, subject, text, user_id
                     FROM emails ORDER BY send_date_email""")) {
            List<List<String>> linhas = new ArrayList<>();
            while (rs.next()) {
                List<String> linha = new ArrayList<>();
                for (int i = 1; i <= 9; i++) {
                    linha.add(rs.getString(i));
                }
                linhas.add(linha);
            }
            return linhas;
        }
    }
}
