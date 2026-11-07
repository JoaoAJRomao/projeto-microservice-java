package com.ms.email.configs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;

/**
 * Escolhe o endereço que a réplica registra no Eureka (ADR-0005).
 *
 * <p>No docker-compose a réplica está em três redes, e a ordem das interfaces no container é
 * arbitrária: o IP da primeira pode ser o da rede do banco, que o gateway não alcança. O endereço
 * certo é o da interface pela qual a réplica chega ao discovery-server, que só existe na rede
 * "edge" (a mesma do gateway). Um {@code connect} UDP só consulta a tabela de rotas; nenhum pacote
 * sai. Se o host do registro não resolver (registro parado), a réplica registra o hostname do
 * container, que o DNS do Docker resolve para o IP certo em cada rede.</p>
 */
public class EurekaEnderecoDaRede implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(EurekaEnderecoDaRede.class);

    static final String IP = "eureka.instance.ip-address";
    static final String PREFERIR_IP = "eureka.instance.prefer-ip-address";
    static final String HOSTNAME = "eureka.instance.hostname";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (!env.getProperty("eureka.client.enabled", Boolean.class, true) || env.containsProperty(IP)) {
            return;
        }
        String zona = env.getProperty("eureka.client.service-url.defaultZone");
        if (zona == null || zona.isBlank()) {
            return;
        }
        URI registro = URI.create(zona.split(",")[0].trim());
        int porta = registro.getPort() > 0 ? registro.getPort() : 80;

        try (DatagramSocket rota = new DatagramSocket()) {
            InetAddress destino = InetAddress.getByName(registro.getHost());
            rota.connect(new InetSocketAddress(destino, porta));
            InetAddress local = rota.getLocalAddress();
            if (!local.isLoopbackAddress() && !local.isAnyLocalAddress()) {
                definir(env, Map.of(IP, local.getHostAddress()));
            }
        } catch (Exception semRota) {
            String hostname = env.getProperty("HOSTNAME");
            if (hostname != null && !hostname.isBlank()) {
                log.warn("Registro {} não resolveu; a réplica se registra pelo hostname {}", registro.getHost(), hostname);
                definir(env, Map.of(PREFERIR_IP, "false", HOSTNAME, hostname));
            }
        }
    }

    private static void definir(ConfigurableEnvironment env, Map<String, Object> propriedades) {
        env.getPropertySources().addFirst(new MapPropertySource("eurekaEnderecoDaRede", propriedades));
    }

    /** Depois do application.yaml e das variáveis de ambiente. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
