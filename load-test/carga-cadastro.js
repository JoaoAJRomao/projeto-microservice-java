// =============================================================================
//  Teste de carga — medição de p50 / p95 / p99 / throughput contra os SLOs
//
//  Via Docker (recomendado, não precisa instalar nada):
//    docker compose --profile loadtest run --rm k6
//    docker compose --profile loadtest run --rm -e RATE=100 -e DURATION=3m k6
//
//  Com k6 instalado na máquina:
//    k6 run -e RATE=50 -e DURATION=2m -e RESULTS_DIR=docs/performance/resultados load-test/carga-cadastro.js
//
//  Saída: resumo no terminal + docs/performance/resultados/resultado-k6.md (tabela pronta
//  para o relatório) + resultado-k6.json (dados brutos).
// =============================================================================
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// Os dois serviços ficam atrás do api-gateway (ADR-0005); as variáveis continuam separadas.
const BASE_USER = __ENV.BASE_URL_USER || 'http://localhost:8080';
const BASE_EMAIL = __ENV.BASE_URL_EMAIL || 'http://localhost:8080';
const RATE = parseInt(__ENV.RATE || '50', 10);          // requisições/s por endpoint
const DURATION = __ENV.DURATION || '2m';                 // duração da fase medida
const WARMUP = '20s';                                    // aquecimento da JVM (não entra na medição)
const E2E_RATE = 2;                                      // cadastros/s acompanhados até o e-mail
const E2E_TIMEOUT_MS = 10000;                            // prazo do SLO-4
const RESULTS_DIR = __ENV.RESULTS_DIR || '.';

// ---- SLOs (mesmos valores de docs/arquitetura.md, seção 5) ----------------
const SLO = {
  cadastro: { p95: 200, p99: 500 },      // POST /user        (ms)
  listagem: { p95: 150, p99: 300 },      // GET  /user        (ms)
  e2e:      { p95: 2000, p99: 5000 },    // 201 -> e-mail registrado no ms-email (ms)
  erro: 0.005,                           // até 0,5% de falhas (disponibilidade 99,5%)
  entregaNoPrazo: 0.99,                  // 99% dos e-mails processados em até 10 s
};

const emailE2E = new Trend('email_e2e_latency', true);
const emailNoPrazo = new Rate('email_processado_no_prazo');
const emailSent = new Rate('email_status_sent');

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    aquecimento: {
      executor: 'constant-arrival-rate', exec: 'cadastrar',
      rate: 10, timeUnit: '1s', duration: WARMUP, preAllocatedVUs: 10, maxVUs: 50,
    },
    cadastro: {
      executor: 'constant-arrival-rate', exec: 'cadastrar', startTime: WARMUP,
      rate: RATE, timeUnit: '1s', duration: DURATION, preAllocatedVUs: 50, maxVUs: 300,
    },
    listagem: {
      executor: 'constant-arrival-rate', exec: 'listar', startTime: WARMUP,
      rate: RATE, timeUnit: '1s', duration: DURATION, preAllocatedVUs: 20, maxVUs: 200,
    },
    ponta_a_ponta: {
      executor: 'constant-arrival-rate', exec: 'pontaAPonta', startTime: WARMUP,
      rate: E2E_RATE, timeUnit: '1s', duration: DURATION, preAllocatedVUs: 30, maxVUs: 100,
    },
  },
  thresholds: {
    'http_req_duration{scenario:cadastro}': [`p(95)<${SLO.cadastro.p95}`, `p(99)<${SLO.cadastro.p99}`],
    'http_req_failed{scenario:cadastro}': [`rate<${SLO.erro}`],
    'http_reqs{scenario:cadastro}': ['count>0'],
    'http_req_duration{scenario:listagem}': [`p(95)<${SLO.listagem.p95}`, `p(99)<${SLO.listagem.p99}`],
    'http_req_failed{scenario:listagem}': [`rate<${SLO.erro}`],
    'http_reqs{scenario:listagem}': ['count>0'],
    'dropped_iterations{scenario:cadastro}': ['count>=0'],
    'dropped_iterations{scenario:listagem}': ['count>=0'],
    email_e2e_latency: [`p(95)<${SLO.e2e.p95}`, `p(99)<${SLO.e2e.p99}`],
    email_processado_no_prazo: [`rate>${SLO.entregaNoPrazo}`],
  },
};

function novoUsuario() {
  const sufixo = `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
  return JSON.stringify({ name: `Usuario Carga ${sufixo}`, email: `carga-${sufixo}@example.com` });
}

const JSON_HEADERS = { 'Content-Type': 'application/json' };

// ---- cenários -------------------------------------------------------------
export function cadastrar() {
  const res = http.post(`${BASE_USER}/user`, novoUsuario(), {
    headers: JSON_HEADERS, tags: { name: 'POST /user' },
  });
  check(res, {
    'POST /user -> 201': (r) => r.status === 201,
    'devolve X-Correlation-ID': (r) => !!r.headers['X-Correlation-Id'] || !!r.headers['X-Correlation-ID'],
  });
}

export function listar() {
  const res = http.get(`${BASE_USER}/user?page=0&size=20`, { tags: { name: 'GET /user' } });
  check(res, { 'GET /user -> 200': (r) => r.status === 200 });
}

/** Cadastra e acompanha até o ms-email registrar o e-mail (mede o fluxo assíncrono). */
export function pontaAPonta() {
  const res = http.post(`${BASE_USER}/user`, novoUsuario(), {
    headers: JSON_HEADERS, tags: { name: 'POST /user (e2e)' },
  });
  if (res.status !== 201) {
    emailNoPrazo.add(false);
    return;
  }
  const userId = res.json('userId');
  const inicio = Date.now();

  while (Date.now() - inicio < E2E_TIMEOUT_MS) {
    const r = http.get(`${BASE_EMAIL}/emails/user/${userId}`, { tags: { name: 'GET /emails/user/{id}' } });
    if (r.status === 200) {
      const emails = r.json();
      if (Array.isArray(emails) && emails.length > 0) {
        emailE2E.add(Date.now() - inicio);
        emailNoPrazo.add(true);
        emailSent.add(emails[0].statusEmail === 'SENT');
        return;
      }
    }
    sleep(0.05); // resolução da medição: ~50 ms
  }
  emailNoPrazo.add(false);
}

// ---- relatório ------------------------------------------------------------
function segundos(duracao) {
  let total = 0;
  const re = /(\d+)(h|m|s)/g;
  let m;
  while ((m = re.exec(duracao)) !== null) {
    total += parseInt(m[1], 10) * (m[2] === 'h' ? 3600 : m[2] === 'm' ? 60 : 1);
  }
  return total || 1;
}

const fmt = (v) => (v === undefined || v === null ? 'n/d' : Number(v).toFixed(1));
const pct = (v) => (v === undefined || v === null ? 'n/d' : `${(v * 100).toFixed(2)}%`);

export function handleSummary(data) {
  const met = (nome) => (data.metrics[nome] ? data.metrics[nome].values : {});
  const dur = segundos(DURATION);
  const linhas = [];
  const violacoes = [];

  const endpoint = (rotulo, cenario, slo) => {
    const d = met(`http_req_duration{scenario:${cenario}}`);
    const reqs = met(`http_reqs{scenario:${cenario}}`).count || 0;
    const falhas = met(`http_req_failed{scenario:${cenario}}`).rate;
    const descartadas = met(`dropped_iterations{scenario:${cenario}}`).count || 0;
    const ok95 = d['p(95)'] < slo.p95;
    const ok99 = d['p(99)'] < slo.p99;
    const okErro = (falhas || 0) < SLO.erro;
    if (!ok95) violacoes.push(`${rotulo}: p95 ${fmt(d['p(95)'])} ms ≥ ${slo.p95} ms`);
    if (!ok99) violacoes.push(`${rotulo}: p99 ${fmt(d['p(99)'])} ms ≥ ${slo.p99} ms`);
    if (!okErro) violacoes.push(`${rotulo}: taxa de erro ${pct(falhas)} ≥ ${pct(SLO.erro)}`);
    linhas.push(`| ${rotulo} | ${reqs} | ${(reqs / dur).toFixed(1)} | ${fmt(d.med)} | ${fmt(d['p(95)'])} | ${fmt(d['p(99)'])} | ${fmt(d.max)} | ${pct(falhas)} | ${descartadas} | p95<${slo.p95} / p99<${slo.p99} | ${ok95 && ok99 && okErro ? '✅ atende' : '❌ viola'} |`);
  };

  endpoint('POST /user', 'cadastro', SLO.cadastro);
  endpoint('GET /user?size=20', 'listagem', SLO.listagem);

  const e2e = met('email_e2e_latency');
  const noPrazo = met('email_processado_no_prazo').rate;
  const sent = met('email_status_sent').rate;
  const ok95 = e2e['p(95)'] < SLO.e2e.p95;
  const ok99 = e2e['p(99)'] < SLO.e2e.p99;
  const okPrazo = (noPrazo || 0) > SLO.entregaNoPrazo;
  if (!ok95) violacoes.push(`E-mail ponta a ponta: p95 ${fmt(e2e['p(95)'])} ms ≥ ${SLO.e2e.p95} ms`);
  if (!ok99) violacoes.push(`E-mail ponta a ponta: p99 ${fmt(e2e['p(99)'])} ms ≥ ${SLO.e2e.p99} ms`);
  if (!okPrazo) violacoes.push(`E-mails processados em até 10 s: ${pct(noPrazo)} ≤ ${pct(SLO.entregaNoPrazo)}`);

  const agora = new Date().toISOString();
  const md = `# Resultado do teste de carga (gerado automaticamente pelo k6)

- **Data/hora (UTC):** ${agora}
- **Carga:** ${RATE} req/s em POST /user + ${RATE} req/s em GET /user, simultâneos, por ${DURATION} (após ${WARMUP} de aquecimento); ${E2E_RATE} cadastros/s acompanhados até o e-mail
- **Executor:** constant-arrival-rate (modelo aberto: a taxa não cai se o sistema ficar lento)
- **Máquina:** _preencher (CPU, RAM, SO, Docker Desktop/Engine)_

## Latência e throughput por endpoint

| Endpoint | Requisições | Throughput (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | máx (ms) | Erros | Iterações descartadas | SLO | Resultado |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---|
${linhas.join('\n')}

## Fluxo assíncrono (cadastro → fila → e-mail registrado)

| Métrica | p50 (ms) | p95 (ms) | p99 (ms) | máx (ms) | Processados em até 10 s | Status SENT | SLO | Resultado |
|---|---:|---:|---:|---:|---:|---:|---|---|
| Latência ponta a ponta | ${fmt(e2e.med)} | ${fmt(e2e['p(95)'])} | ${fmt(e2e['p(99)'])} | ${fmt(e2e.max)} | ${pct(noPrazo)} | ${pct(sent)} | p95<${SLO.e2e.p95} / p99<${SLO.e2e.p99} / ≥99% em 10 s | ${ok95 && ok99 && okPrazo ? '✅ atende' : '❌ viola'} |

## Conclusão automática

${violacoes.length === 0
    ? `**Todos os SLOs foram atendidos** na carga de ${RATE} req/s por endpoint.`
    : `**SLOs violados:**\n\n${violacoes.map((v) => `- ${v}`).join('\n')}`}

> "Iterações descartadas" > 0 indica que o k6 não conseguiu manter a taxa pedida — o sistema (ou a máquina) chegou ao limite.
`;

  const texto = `\n${md}\n`;
  return {
    stdout: texto,
    [`${RESULTS_DIR}/resultado-k6.md`]: md,
    [`${RESULTS_DIR}/resultado-k6.json`]: JSON.stringify(data, null, 2),
  };
}
