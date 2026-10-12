/*
 * Núcleo da interface de demonstração: sessão, estado local, chamadas HTTP,
 * registro de chamadas e utilitários. Os demais arquivos dependem deste.
 */

var CHAVES = {
  token: 'alocacreche.token',
  municipio: 'alocacreche.municipioId',
  expira: 'alocacreche.expiraEm',
  estado: 'alocacreche.estado',
  log: 'alocacreche.log',
  roteiro: 'alocacreche.roteiro',
  modo: 'alocacreche.modo',
  aba: 'alocacreche.aba'
};

// O token fica apenas no sessionStorage desta aba e nunca é exibido ou registrado.
var token = sessionStorage.getItem(CHAVES.token);
var municipioId = Number(sessionStorage.getItem(CHAVES.municipio));

function $(id) { return document.getElementById(id); }

function $$(seletor, raiz) { return Array.prototype.slice.call((raiz || document).querySelectorAll(seletor)); }

// Cria um elemento: el('div', {class: 'x', texto: 'oi'}, [filhos])
function el(tag, atributos, filhos) {
  var no = document.createElement(tag);
  Object.keys(atributos || {}).forEach(function (chave) {
    if (chave === 'texto') { no.textContent = atributos[chave]; }
    else if (chave === 'html') { no.innerHTML = atributos[chave]; }
    else if (chave === 'class') { no.className = atributos[chave]; }
    else { no.setAttribute(chave, atributos[chave]); }
  });
  (filhos || []).forEach(function (filho) {
    if (filho === null || filho === undefined) { return; }
    no.appendChild(typeof filho === 'string' ? document.createTextNode(filho) : filho);
  });
  return no;
}

function esperar(ms) { return new Promise(function (resolver) { setTimeout(resolver, ms); }); }

// ---------------------------------------------------------------------------
// Estado local (listas da sessão da tela; não é o banco)
// ---------------------------------------------------------------------------

function novoEstado() {
  return {
    creches: [], turmas: [], responsaveis: [], criancas: [], inscricoes: [],
    matriculas: [], eventos: [], turmaAtual: null, matriculaAtual: null,
    municipioRel: null, periodo: null, intervalo: 0, seqEvento: 0
  };
}

var estado = lerJson(CHAVES.estado) || novoEstado();

function lerJson(chave) {
  try { return JSON.parse(sessionStorage.getItem(chave)); } catch (e) { return null; }
}

function salvarEstado() {
  sessionStorage.setItem(CHAVES.estado, JSON.stringify(estado));
}

function reiniciarEstadoLocal() {
  estado = novoEstado();
  salvarEstado();
  limparLog();
}

function acharPorId(lista, id) {
  for (var i = 0; i < lista.length; i++) { if (lista[i].id === id) { return lista[i]; } }
  return null;
}

// ---------------------------------------------------------------------------
// Sessão e JWT (somente o payload é decodificado; o token nunca vai para a tela)
// ---------------------------------------------------------------------------

function lerPayloadJwt(valor) {
  try {
    var parte = valor.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    while (parte.length % 4) { parte += '='; }
    return JSON.parse(decodeURIComponent(escape(atob(parte))));
  } catch (e) {
    return null;
  }
}

function permissoesDoToken() {
  var payload = token ? lerPayloadJwt(token) : null;
  if (!payload) { return []; }
  var perms = payload.perms || payload.permissoes || [];
  return Array.isArray(perms) ? perms : [];
}

// Deduz o perfil pelas permissões do token: GESTOR lê relatórios, ATENDENTE opera inscrição
function perfilDoToken() {
  var perms = permissoesDoToken();
  if (perms.indexOf('*') >= 0) { return 'ADMIN'; }
  if (!perms.length) { return 'sem permissões'; }
  if (perms.some(function (p) { return p.indexOf('relatorios:') === 0; })) { return 'GESTOR'; }
  if (perms.some(function (p) { return p.indexOf('inscricao:') === 0; })) { return 'ATENDENTE'; }
  return 'personalizado';
}

// Equivalente simples ao teste de permissão do Sa-Token (curinga * e prefixo:*)
function temPermissao(perms, exigida) {
  return perms.some(function (p) {
    if (p === '*' || p === exigida) { return true; }
    return p.slice(-2) === ':*' && exigida.indexOf(p.slice(0, -1)) === 0;
  });
}

// Adultera um caractere da assinatura sem expor o token em lugar nenhum
function tokenAdulterado() {
  if (!token) { return 'token.invalido.x'; }
  var ultimo = token.slice(-1);
  return token.slice(0, -1) + (ultimo === 'A' ? 'B' : 'A');
}

function limparSessao() {
  sessionStorage.removeItem(CHAVES.token);
  sessionStorage.removeItem(CHAVES.municipio);
  sessionStorage.removeItem(CHAVES.expira);
}

// Com manterListas=true (troca de usuário) as listas locais da tela são preservadas no próximo login;
// nos demais casos (sessão expirada, token inválido, demonstração reiniciada) o login as descarta.
function irParaLogin(manterListas) {
  limparSessao();
  if (manterListas === true) { sessionStorage.setItem('alocacreche.manterListas', '1'); }
  window.location.replace('/login.html');
}

// ---------------------------------------------------------------------------
// Mascaramento (LGPD): CPF, renda, vulnerabilidade e token nunca vão para o registro
// ---------------------------------------------------------------------------

function mascarar(texto) {
  if (!texto) { return ''; }
  return String(texto)
    .replace(/("token"\s*:\s*")[^"]+(")/g, '$1***$2')
    .replace(/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, 'eyJ***')
    .replace(/(Bearer\s+)[A-Za-z0-9._-]+/g, '$1***')
    .replace(/("cpf"\s*:\s*")[^"]*(\d{2})(")/g, '$1***.***.***-$2$3')
    .replace(/\b\d{3}\.\d{3}\.\d{3}-\d{2}\b/g, function (m) { return '***.***.***-' + m.slice(-2); })
    .replace(/("rendaPerCapita"\s*:\s*)[0-9.]+/g, '$1"***"')
    .replace(/("criterioVulnerabilidade"\s*:\s*)(true|false)/g, '$1"***"')
    .replace(/("senha"\s*:\s*")[^"]*(")/g, '$1***$2');
}

// ---------------------------------------------------------------------------
// Chamadas HTTP com registro (mesma origem: o Gateway serve a página e as APIs)
// ---------------------------------------------------------------------------

var logEntradas = lerJson(CHAVES.log) || [];
var logSeq = logEntradas.length ? logEntradas[0].id + 1 : 1;
var ouvintesDeChamada = [];

function aoChamar(funcao) { ouvintesDeChamada.push(funcao); }

/*
 * opcoes.corpo: objeto (ou texto bruto) enviado como JSON
 * opcoes.token: undefined usa o token da sessão; null não envia token; texto envia esse valor
 * opcoes.automatica: chamada de atualização periódica (filtrada por padrão no registro)
 * opcoes.rotulo: legenda curta exibida no registro
 * opcoes.sem401: não volta ao login quando a resposta é 401 (testes de segurança)
 */
async function api(metodo, caminho, opcoes) {
  opcoes = opcoes || {};
  var cabecalhos = {};
  var corpoTexto = null;
  if (opcoes.corpo !== undefined && opcoes.corpo !== null) {
    cabecalhos['Content-Type'] = 'application/json';
    corpoTexto = typeof opcoes.corpo === 'string' ? opcoes.corpo : JSON.stringify(opcoes.corpo);
  }
  var tokenUsado = opcoes.token === undefined ? token : opcoes.token;
  if (tokenUsado) { cabecalhos['Authorization'] = 'Bearer ' + tokenUsado; }

  var inicio = performance.now();
  var resposta = null;
  var texto = '';
  try {
    resposta = await fetch(caminho, { method: metodo, headers: cabecalhos, body: corpoTexto === null ? undefined : corpoTexto });
    texto = await resposta.text();
  } catch (erro) {
    var falha = registrarChamada(metodo, caminho, 0, performance.now() - inicio, corpoTexto, 'falha de rede: gateway ou backend indisponível', !!tokenUsado, opcoes, null);
    return { ok: false, status: 0, dados: null, texto: '', ms: falha.ms, headers: null, erroRede: true };
  }
  var ms = performance.now() - inicio;
  var dados = null;
  try { dados = texto ? JSON.parse(texto) : null; } catch (e) { dados = texto; }
  var retryAfter = resposta.headers.get('Retry-After');
  registrarChamada(metodo, caminho, resposta.status, ms, corpoTexto, texto, !!tokenUsado, opcoes, retryAfter);

  if (resposta.status === 401 && tokenUsado && tokenUsado === token && !opcoes.sem401) {
    avisar('Sessão expirada ou inválida. Voltando ao login...', 'erro');
    setTimeout(irParaLogin, 1200);
  }
  var resultado = { ok: resposta.ok, status: resposta.status, dados: dados, texto: texto, ms: ms, retryAfter: retryAfter, erroRede: false };
  ouvintesDeChamada.forEach(function (ouvinte) { ouvinte(metodo, caminho, resultado, opcoes); });
  return resultado;
}

function urlAbsoluta(caminho) { return window.location.origin + caminho; }

// Monta o comando curl equivalente; o token nunca é incluído
function montarCurl(entrada, corpo) {
  var partes = ["curl -s -i -X " + entrada.metodo + " '" + urlAbsoluta(entrada.caminho) + "'"];
  if (entrada.temToken) { partes.push("-H 'Authorization: Bearer $TOKEN'"); }
  if (corpo) {
    partes.push("-H 'Content-Type: application/json'");
    partes.push("-d '" + corpo.replace(/'/g, "'\\''") + "'");
  }
  return partes.join(' \\\n  ');
}

function registrarChamada(metodo, caminho, status, ms, corpoReq, textoResp, temToken, opcoes, retryAfter) {
  var entrada = {
    id: logSeq++,
    hora: new Date().toLocaleTimeString('pt-BR'),
    metodo: metodo,
    caminho: caminho,
    status: status,
    ms: Math.round(ms),
    corpoReq: mascarar(corpoReq || ''),
    corpoResp: mascarar(textoResp || '').slice(0, 600),
    temToken: temToken,
    automatica: !!opcoes.automatica,
    rotulo: opcoes.rotulo || '',
    retryAfter: retryAfter || ''
  };
  entrada.curlSeguro = montarCurl(entrada, entrada.corpoReq);
  // O corpo bruto fica só em memória (não é gravado no sessionStorage)
  entrada._curlBruto = corpoReq ? montarCurl(entrada, corpoReq) : entrada.curlSeguro;
  logEntradas.unshift(entrada);
  if (logEntradas.length > 200) { logEntradas.length = 200; }
  persistirLog();
  renderizarLog();
  return entrada;
}

function persistirLog() {
  var copia = logEntradas.slice(0, 120).map(function (e) {
    var item = {};
    Object.keys(e).forEach(function (k) { if (k.charAt(0) !== '_') { item[k] = e[k]; } });
    return item;
  });
  try { sessionStorage.setItem(CHAVES.log, JSON.stringify(copia)); } catch (e) { }
}

function limparLog() {
  logEntradas = [];
  persistirLog();
  renderizarLog();
}

function classeStatus(status) { return status >= 200 && status < 400 ? 'ok' : 'erro'; }

function entradasFiltradas() {
  var busca = ($('logBusca').value || '').toLowerCase();
  var faixa = $('logStatus').value;
  var metodo = $('logMetodo').value;
  var mostrarAuto = $('logAuto').checked;
  return logEntradas.filter(function (e) {
    if (e.automatica && !mostrarAuto && e.status >= 200 && e.status < 400) { return false; }
    if (metodo && e.metodo !== metodo) { return false; }
    if (faixa !== '' && String(Math.floor(e.status / 100)) !== faixa) { return false; }
    if (busca && (e.metodo + ' ' + e.caminho + ' ' + e.rotulo + ' ' + e.corpoResp).toLowerCase().indexOf(busca) < 0) { return false; }
    return true;
  });
}

function renderizarLog() {
  var lista = $('logLista');
  if (!lista) { return; }
  var aberto = {};
  $$('.registro-item.aberto', lista).forEach(function (n) { aberto[n.getAttribute('data-id')] = true; });
  var filtradas = entradasFiltradas();
  lista.textContent = '';
  filtradas.slice(0, 120).forEach(function (e) {
    var item = el('div', { class: 'registro-item r-' + classeStatus(e.status) + (aberto[e.id] ? ' aberto' : ''), 'data-id': e.id }, [
      el('div', { class: 'l1' }, [
        el('span', { class: 'hora', texto: e.hora }),
        el('span', { class: 'm', texto: e.metodo }),
        el('span', { class: 'rota', texto: e.caminho, title: e.caminho }),
        el('span', { class: 's ' + classeStatus(e.status), texto: e.status === 0 ? 'rede' : String(e.status) }),
        el('span', { class: 'hora', texto: e.ms + ' ms' })
      ])
    ]);
    if (e.rotulo) { item.appendChild(el('div', { class: 'rotulo', texto: e.rotulo + (e.temToken ? '' : ' (sem token)') })); }
    var detalhe = el('div', { class: 'registro-detalhe tecnico' });
    if (e.corpoReq) { detalhe.appendChild(el('div', { texto: 'Requisição:' })); detalhe.appendChild(el('pre', { texto: e.corpoReq })); }
    detalhe.appendChild(el('div', { texto: 'Resposta' + (e.retryAfter ? ' (Retry-After ' + e.retryAfter + ' s)' : '') + ':' }));
    detalhe.appendChild(el('pre', { texto: e.corpoResp || '(vazia)' }));
    var botao = el('button', { class: 'mini sec', texto: 'Copiar como curl' });
    botao.onclick = function (evento) { evento.stopPropagation(); copiar(curlDaEntrada(e.id), 'curl copiado (sem token)'); };
    detalhe.appendChild(botao);
    item.appendChild(detalhe);
    item.onclick = function () { item.classList.toggle('aberto'); };
    lista.appendChild(item);
  });
  if (!filtradas.length) { lista.appendChild(el('div', { class: 'registro-item vazio', texto: 'Nenhuma chamada para os filtros atuais.' })); }
  $('logContador').textContent = filtradas.length + '/' + logEntradas.length;
}

function curlDaEntrada(id) {
  for (var i = 0; i < logEntradas.length; i++) {
    if (logEntradas[i].id === id) { return logEntradas[i]._curlBruto || logEntradas[i].curlSeguro; }
  }
  return '';
}

function copiarUltimaComoCurl() {
  var filtradas = entradasFiltradas();
  if (!filtradas.length) { avisar('Não há chamadas para copiar.', 'erro'); return; }
  copiar(curlDaEntrada(filtradas[0].id), 'curl copiado (sem token)');
}

function iniciarRegistro() {
  ['logBusca', 'logStatus', 'logMetodo', 'logAuto'].forEach(function (id) { $(id).addEventListener('input', renderizarLog); $(id).addEventListener('change', renderizarLog); });
  $('btnLogLimpar').onclick = limparLog;
  $('btnLogCopiar').onclick = copiarUltimaComoCurl;
  $('btnRecolher').onclick = function () { $('corpo').classList.toggle('sem-registro'); };
  renderizarLog();
}

// ---------------------------------------------------------------------------
// Utilitários de interface
// ---------------------------------------------------------------------------

// Notificação não bloqueante (substitui alert)
function avisar(mensagem, tipo) {
  var caixa = el('div', { class: 'toast ' + (tipo || ''), texto: mensagem });
  $('toasts').appendChild(caixa);
  setTimeout(function () { if (caixa.parentNode) { caixa.parentNode.removeChild(caixa); } }, 3600);
}

function copiar(texto, mensagem) {
  var sucesso = function () { avisar(mensagem || 'Copiado.', 'ok'); };
  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(texto).then(sucesso, function () { copiarAlternativo(texto, sucesso); });
  } else {
    copiarAlternativo(texto, sucesso);
  }
}

function copiarAlternativo(texto, sucesso) {
  var area = el('textarea', { style: 'position:fixed;opacity:0' });
  area.value = texto;
  document.body.appendChild(area);
  area.select();
  try { document.execCommand('copy'); sucesso(); } catch (e) { avisar('Não foi possível copiar.', 'erro'); }
  document.body.removeChild(area);
}

// Atualiza um valor na tela e destaca quando ele muda (mostra a consistência eventual)
function definirValor(elemento, valor) {
  var novo = (valor === undefined || valor === null) ? '-' : String(valor);
  var antigo = elemento.textContent;
  elemento.textContent = novo;
  if (antigo !== novo && antigo !== '-' && antigo !== '') {
    elemento.classList.remove('mudou');
    void elemento.offsetWidth;
    elemento.classList.add('mudou');
  }
}

function selo(texto, classe) { return el('span', { class: 'selo ' + (classe || ''), texto: texto }); }

// Selo colorido com o status HTTP
function seloStatus(status) {
  var classe = status >= 200 && status < 300 ? 'ok' : (status >= 400 || status === 0 ? 'erro' : 'aviso');
  return selo(status === 0 ? 'rede' : String(status), classe);
}

function cpfAleatorio() {
  var n = '';
  for (var i = 0; i < 11; i++) { n += Math.floor(Math.random() * 10); }
  return n.slice(0, 3) + '.' + n.slice(3, 6) + '.' + n.slice(6, 9) + '-' + n.slice(9);
}

function sufixoAleatorio() { return Math.random().toString(36).slice(2, 7); }

function haversineKm(lat1, lon1, lat2, lon2) {
  var rad = Math.PI / 180;
  var dLat = (lat2 - lat1) * rad;
  var dLon = (lon2 - lon1) * rad;
  var a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
  return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function numero(valor, casas) {
  if (valor === null || valor === undefined || isNaN(valor)) { return '-'; }
  return Number(valor).toLocaleString('pt-BR', { minimumFractionDigits: casas, maximumFractionDigits: casas });
}

// Preenche um select com opções {valor, rotulo}; preserva a seleção atual quando possível
function preencherSelect(select, opcoes, vazio) {
  var atual = select.value;
  select.textContent = '';
  if (!opcoes.length) { select.appendChild(el('option', { value: '', texto: vazio || '(nenhum)' })); return; }
  opcoes.forEach(function (o) { select.appendChild(el('option', { value: String(o.valor), texto: o.rotulo })); });
  if (atual && opcoes.some(function (o) { return String(o.valor) === atual; })) { select.value = atual; }
}

function periodoPadrao() {
  var d = new Date();
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');
}

// Converte o mês (YYYY-MM) no primeiro dia, formato esperado pelos relatórios
function periodoComoData(mes) { return (mes || periodoPadrao()) + '-01'; }

// Descreve um erro devolvido pela API de forma legível
function mensagemDeErro(resposta) {
  if (resposta.erroRede) { return 'falha de rede: serviços fora do ar?'; }
  var dados = resposta.dados;
  var texto = dados && typeof dados === 'object' && dados.erro ? dados.erro : '';
  if (dados && dados.campos) { texto += ' (' + dados.campos.join(', ') + ')'; }
  return resposta.status + (texto ? ' ' + texto : '');
}
