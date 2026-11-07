package com.ms.gateway;

import com.ms.gateway.config.RotasConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.discovery.simple.InstanceProperties;
import org.springframework.cloud.client.discovery.simple.reactive.SimpleReactiveDiscoveryProperties;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gateway real (rotas, filtros, circuit breaker) com o registro trocado por instâncias estáticas
 * (simple discovery) que apontam para {@link ServidorFalso}s. Cada teste começa com
 * {@code ms-user} = [userA, userB], {@code ms-email} = [email] e os circuitos fechados.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.cloud.loadbalancer.cache.enabled=false"
})
abstract class GatewayTestBase {

    static final ServidorFalso userA = new ServidorFalso("user-a");
    static final ServidorFalso userB = new ServidorFalso("user-b");
    static final ServidorFalso email = new ServidorFalso("email");

    private static final AtomicInteger sequencia = new AtomicInteger();

    private final Map<String, List<InstanceProperties>> registro = new HashMap<>();

    final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @LocalServerPort
    int portaGateway;

    @Autowired
    SimpleReactiveDiscoveryProperties discovery;

    @Autowired
    ReactiveResilience4JCircuitBreakerFactory circuitBreakers;

    @BeforeEach
    void estadoInicial() {
        for (ServidorFalso s : List.of(userA, userB, email)) {
            s.reiniciar();
        }
        instancias(RotasConfig.MS_USER, userA, userB);
        instancias(RotasConfig.MS_EMAIL, email);
        circuitBreakers.getCircuitBreakerRegistry().getAllCircuitBreakers().forEach(cb -> cb.reset());
    }

    void instancias(String servico, ServidorFalso... servidores) {
        registrar(servico, Arrays.stream(servidores).map(s -> instancia(servico, s.nome(), s.porta())).toList());
    }

    /** Uma instância registrada numa porta onde nada escuta (conexão recusada), seguida das outras. */
    void instanciaRecusandoConexao(String servico, ServidorFalso... outras) {
        List<InstanceProperties> lista = new ArrayList<>();
        lista.add(instancia(servico, "recusa", portaLivre()));
        for (ServidorFalso s : outras) {
            lista.add(instancia(servico, s.nome(), s.porta()));
        }
        registrar(servico, lista);
    }

    private synchronized void registrar(String servico, List<InstanceProperties> lista) {
        registro.put(servico, lista);
        discovery.setInstances(new HashMap<>(registro));
    }

    private static InstanceProperties instancia(String servico, String nome, int porta) {
        InstanceProperties i = new InstanceProperties();
        i.setInstanceId(servico + ":" + nome);
        i.setServiceId(servico);
        i.setHost("localhost");
        i.setPort(porta);
        i.setUri(URI.create("http://localhost:" + porta));
        return i;
    }

    HttpResponse<String> get(String caminho, String... headers) {
        return enviar(base(caminho, headers).GET().build());
    }

    HttpResponse<String> post(String caminho, String json, String... headers) {
        return enviar(base(caminho, headers)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    static String novoEmail() {
        return "pessoa" + sequencia.incrementAndGet() + "@exemplo.com";
    }

    private HttpRequest.Builder base(String caminho, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + portaGateway + caminho))
                .timeout(Duration.ofSeconds(30));
        if (headers.length > 0) {
            b.headers(headers);
        }
        return b;
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

    private static int portaLivre() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
