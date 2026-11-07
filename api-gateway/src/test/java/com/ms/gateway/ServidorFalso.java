package com.ms.gateway;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Réplica falsa de um serviço: registra cada requisição que chega e responde como o teste mandar
 * (status, corpo, demora, ou fechar a conexão sem responder). {@link #desligar()} solta a porta,
 * e a conexão passa a ser recusada.
 */
final class ServidorFalso {

    record Requisicao(String metodo, String caminho, Map<String, String> headers, String corpo) {
        String header(String nome) {
            return headers.get(nome.toLowerCase(Locale.ROOT));
        }
    }

    record Resposta(int status, String contentType, String corpo, Duration atraso, boolean fecharSemResponder) {
        static Resposta json(int status, String corpo) {
            return new Resposta(status, "application/json", corpo, Duration.ZERO, false);
        }

        static Resposta problema(int status, String corpo) {
            return new Resposta(status, "application/problem+json", corpo, Duration.ZERO, false);
        }

        static Resposta fecharConexao() {
            return new Resposta(0, null, null, Duration.ZERO, true);
        }

        Resposta depoisDe(Duration atraso) {
            return new Resposta(status, contentType, corpo, atraso, fecharSemResponder);
        }
    }

    private final String nome;
    private final ExecutorService conexoes = Executors.newCachedThreadPool();
    private final List<Requisicao> recebidas = new CopyOnWriteArrayList<>();
    private volatile Function<Requisicao, Resposta> comportamento;
    private volatile ServerSocket socket;
    private final int porta;

    ServidorFalso(String nome) {
        this.nome = nome;
        this.porta = abrir(0);
        padrao();
    }

    int porta() {
        return porta;
    }

    String nome() {
        return nome;
    }

    List<Requisicao> recebidas() {
        return List.copyOf(recebidas);
    }

    /** Volta ao estado inicial: ligado, sem histórico, respondendo 200 com o próprio nome. */
    void reiniciar() {
        ligar();
        recebidas.clear();
        padrao();
    }

    void responder(Resposta resposta) {
        this.comportamento = r -> resposta;
    }

    void responder(Function<Requisicao, Resposta> comportamento) {
        this.comportamento = comportamento;
    }

    synchronized void desligar() {
        if (socket != null) {
            fecharSilenciosamente(socket);
            socket = null;
        }
    }

    synchronized void ligar() {
        if (socket == null) {
            abrir(porta);
        }
    }

    private void padrao() {
        responder(Resposta.json(200, "{\"atendidoPor\":\"" + nome + "\"}"));
    }

    private synchronized int abrir(int portaDesejada) {
        try {
            ServerSocket novo = new ServerSocket();
            novo.setReuseAddress(true);
            novo.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), portaDesejada));
            socket = novo;
            Thread aceitador = new Thread(() -> aceitar(novo), "servidor-falso-" + nome);
            aceitador.setDaemon(true);
            aceitador.start();
            return novo.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("não abriu a porta " + portaDesejada, e);
        }
    }

    private void aceitar(ServerSocket servidor) {
        while (!servidor.isClosed()) {
            try {
                Socket conexao = servidor.accept();
                conexoes.submit(() -> atender(conexao));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void atender(Socket conexao) {
        try (conexao) {
            Requisicao requisicao = ler(conexao.getInputStream());
            if (requisicao == null) {
                return;
            }
            recebidas.add(requisicao);
            Resposta resposta = comportamento.apply(requisicao);
            if (!resposta.atraso().isZero()) {
                Thread.sleep(resposta.atraso().toMillis());
            }
            if (resposta.fecharSemResponder()) {
                conexao.setSoLinger(true, 0);
                return;
            }
            escrever(conexao.getOutputStream(), resposta);
        } catch (IOException | InterruptedException ignorado) {
            // o gateway desistiu da conexão (timeout) antes da resposta
        }
    }

    private static Requisicao ler(InputStream entrada) throws IOException {
        BufferedInputStream in = new BufferedInputStream(entrada);
        String linhaInicial = linha(in);
        if (linhaInicial == null || linhaInicial.isBlank()) {
            return null;
        }
        String[] partes = linhaInicial.split(" ");
        Map<String, String> headers = new LinkedHashMap<>();
        String linha;
        while ((linha = linha(in)) != null && !linha.isEmpty()) {
            int separador = linha.indexOf(':');
            headers.put(linha.substring(0, separador).trim().toLowerCase(Locale.ROOT), linha.substring(separador + 1).trim());
        }
        byte[] corpo;
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            corpo = lerChunked(in);
        } else {
            int tamanho = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            corpo = in.readNBytes(tamanho);
        }
        return new Requisicao(partes[0], partes[1], headers, new String(corpo, StandardCharsets.UTF_8));
    }

    private static byte[] lerChunked(BufferedInputStream in) throws IOException {
        ByteArrayOutputStream corpo = new ByteArrayOutputStream();
        while (true) {
            String tamanhoHex = linha(in);
            int tamanho = Integer.parseInt(tamanhoHex.split(";")[0].trim(), 16);
            if (tamanho == 0) {
                linha(in);
                return corpo.toByteArray();
            }
            corpo.write(in.readNBytes(tamanho));
            linha(in);
        }
    }

    private static String linha(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                bytes.write(b);
            }
        }
        if (b == -1 && bytes.size() == 0) {
            return null;
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static void escrever(OutputStream saida, Resposta resposta) throws IOException {
        byte[] corpo = resposta.corpo() == null ? new byte[0] : resposta.corpo().getBytes(StandardCharsets.UTF_8);
        StringBuilder cabecalho = new StringBuilder()
                .append("HTTP/1.1 ").append(resposta.status()).append(" X\r\n")
                .append("Content-Length: ").append(corpo.length).append("\r\n")
                .append("Connection: close\r\n");
        if (resposta.contentType() != null) {
            cabecalho.append("Content-Type: ").append(resposta.contentType()).append("\r\n");
        }
        cabecalho.append("\r\n");
        saida.write(cabecalho.toString().getBytes(StandardCharsets.US_ASCII));
        saida.write(corpo);
        saida.flush();
    }

    private static void fecharSilenciosamente(ServerSocket s) {
        try {
            s.close();
        } catch (IOException ignorado) {
            // já fechado
        }
    }
}
