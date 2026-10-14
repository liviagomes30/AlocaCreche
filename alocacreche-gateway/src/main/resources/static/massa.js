/*
 * Massa de dados da apresentação: as mesmas creches, turmas e famílias usadas na apresentação. A demonstração guiada da Stack C usa estes dados, e o botão "Carregar dados-base"
 * da aba Roteiro também, para o ensaio. Se mudar algo aqui, mude a cola.
 *
 * Score = renda x 0,4 + vulnerabilidade x 0,3 (distância 0 na inscrição; pesos do município demo).
 */

var MASSA = {
  creches: [
    { chave: 'sol', nome: 'Creche Sol', capacidade: 50, rua: 'Rua Direita', lat: -23.550, lon: -46.633, turmas: [{ faixa: 'MATERNAL', vagas: 3 }, { faixa: 'BERCARIO', vagas: 2 }] },
    { chave: 'girassol', nome: 'Creche Girassol', capacidade: 40, rua: 'Rua Treze de Maio', lat: -23.561, lon: -46.656, turmas: [{ faixa: 'MATERNAL', vagas: 2 }] },
    { chave: 'aurora', nome: 'Creche Aurora', capacidade: 30, rua: 'Rua dos Pinheiros', lat: -23.567, lon: -46.692, turmas: [{ faixa: 'MATERNAL', vagas: 1 }, { faixa: 'PRE_ESCOLA', vagas: 4 }] },
    { chave: 'esperanca', nome: 'Creche Esperança', capacidade: 35, rua: 'Rua da Penha', lat: -23.526, lon: -46.543, turmas: [{ faixa: 'MATERNAL', vagas: 2 }] },
    { chave: 'horizonte', nome: 'Creche Horizonte', capacidade: 45, rua: 'Avenida Santo Amaro', lat: -23.655, lon: -46.709, turmas: [{ faixa: 'BERCARIO', vagas: 3 }] }
  ],
  // Ordem de chegada na fila da Creche Sol (maternal): do menor para o maior score, para a fila "furar" a cada inscrição
  ordemDeChegada: ['fabio', 'elisa', 'hugo', 'gisele', 'diego', 'carla', 'bruno', 'ana'],
  familias: {
    ana: { responsavel: 'Ana Silva', cpf: '100.000.000-01', telefone: '11999990001', email: 'ana.silva@example.com', rua: 'Rua Direita', numero: 10, lat: -23.552, lon: -46.634, crianca: 'Lia Silva', nascimento: '2024-03-10', renda: 350, vulneravel: true, score: 0.70 },
    bruno: { responsavel: 'Bruno Souza', cpf: '100.000.000-02', telefone: '11999990002', email: 'bruno.souza@example.com', rua: 'Rua da Glória', numero: 25, lat: -23.559, lon: -46.635, crianca: 'Davi Souza', nascimento: '2024-05-02', renda: 800, vulneravel: true, score: 0.60 },
    carla: { responsavel: 'Carla Costa', cpf: '100.000.000-03', telefone: '11999990003', email: 'carla.costa@example.com', rua: 'Rua Treze de Maio', numero: 330, lat: -23.563, lon: -46.649, crianca: 'Alice Costa', nascimento: '2023-11-20', renda: 1200, vulneravel: true, score: 0.50 },
    diego: { responsavel: 'Diego Lima', cpf: '100.000.000-04', telefone: '11999990004', email: 'diego.lima@example.com', rua: 'Rua dos Pinheiros', numero: 410, lat: -23.566, lon: -46.683, crianca: 'Heitor Lima', nascimento: '2024-01-15', renda: 450, vulneravel: false, score: 0.40 },
    elisa: { responsavel: 'Elisa Ramos', cpf: '100.000.000-05', telefone: '11999990005', email: 'elisa.ramos@example.com', rua: 'Rua da Penha', numero: 52, lat: -23.528, lon: -46.548, crianca: 'Sofia Ramos', nascimento: '2024-07-08', renda: 1800, vulneravel: false, score: 0.10 },
    fabio: { responsavel: 'Fábio Prado', cpf: '100.000.000-06', telefone: '11999990006', email: 'fabio.prado@example.com', rua: 'Avenida Santo Amaro', numero: 1200, lat: -23.650, lon: -46.705, crianca: 'Pedro Prado', nascimento: '2024-02-11', renda: 4500, vulneravel: false, score: 0.00 },
    gisele: { responsavel: 'Gisele Nunes', cpf: '100.000.000-07', telefone: '11999990007', email: 'gisele.nunes@example.com', rua: 'Rua São Joaquim', numero: 77, lat: -23.554, lon: -46.640, crianca: 'Laura Nunes', nascimento: '2023-12-05', renda: 350, vulneravel: false, score: 0.40 },
    // Famílias extras: só os cenários finais da demonstração da Stack C usam (outras creches, turma lotada)
    irene: { responsavel: 'Irene Dias', cpf: '100.000.000-09', telefone: '11999990009', email: 'irene.dias@example.com', rua: 'Rua Mourato Coelho', numero: 120, lat: -23.569, lon: -46.690, crianca: 'Bia Dias', nascimento: '2024-06-14', renda: 600, vulneravel: true, score: 0.60 },
    julio: { responsavel: 'Júlio Moura', cpf: '100.000.000-10', telefone: '11999990010', email: 'julio.moura@example.com', rua: 'Rua Fradique Coutinho', numero: 88, lat: -23.571, lon: -46.695, crianca: 'Caio Moura', nascimento: '2024-02-27', renda: 1600, vulneravel: false, score: 0.10 },
    karina: { responsavel: 'Karina Reis', cpf: '100.000.000-11', telefone: '11999990011', email: 'karina.reis@example.com', rua: 'Rua Doutor João Ribeiro', numero: 45, lat: -23.524, lon: -46.541, crianca: 'Lucas Reis', nascimento: '2023-10-30', renda: 500, vulneravel: true, score: 0.70 },
    leandro: { responsavel: 'Leandro Cruz', cpf: '100.000.000-12', telefone: '11999990012', email: 'leandro.cruz@example.com', rua: 'Rua Coronel Rodrigues', numero: 200, lat: -23.529, lon: -46.546, crianca: 'Nina Cruz', nascimento: '2024-08-19', renda: 1300, vulneravel: false, score: 0.20 },
    // Esperam nas filas de turmas lotadas das creches Esperança e Aurora (cenário "filas em várias turmas e creches")
    wagner: { responsavel: 'Wagner Pires', cpf: '100.000.000-14', telefone: '11999990014', email: 'wagner.pires@example.com', rua: 'Rua do Cruzeiro', numero: 18, lat: -23.523, lon: -46.540, crianca: 'Davi Pires', nascimento: '2023-12-28', renda: 1900, vulneravel: false, score: 0.10 },
    vera: { responsavel: 'Vera Lopes', cpf: '100.000.000-15', telefone: '11999990015', email: 'vera.lopes@example.com', rua: 'Rua Padre Antônio', numero: 33, lat: -23.527, lon: -46.545, crianca: 'Sara Lopes', nascimento: '2024-05-16', renda: 700, vulneravel: true, score: 0.60 },
    olivia: { responsavel: 'Olívia Gomes', cpf: '100.000.000-16', telefone: '11999990016', email: 'olivia.gomes@example.com', rua: 'Rua Capote Valente', numero: 60, lat: -23.566, lon: -46.693, crianca: 'Ravi Gomes', nascimento: '2024-03-03', renda: 400, vulneravel: false, score: 0.40 },
    // Inscreve-se durante a falha do RabbitMQ (score 0,00: entra no fim da fila da Creche Sol e não muda quem é convocado)
    marcos: { responsavel: 'Marcos Tavares', cpf: '100.000.000-13', telefone: '11999990013', email: 'marcos.tavares@example.com', rua: 'Rua Augusta', numero: 900, lat: -23.553, lon: -46.642, crianca: 'Théo Tavares', nascimento: '2024-09-03', renda: 5000, vulneravel: false, score: 0.00 },
    hugo: { responsavel: 'Hugo Alves', cpf: '100.000.000-08', telefone: '11999990008', email: 'hugo.alves@example.com', rua: 'Rua Boa Vista', numero: 15, lat: -23.545, lon: -46.620, crianca: 'Miguel Alves', nascimento: '2024-04-22', renda: 2500, vulneravel: true, score: 0.30 }
  }
};

// Posição final esperada de cada família quando as 8 estão na fila da Creche Sol (maternal)
var POSICAO_ESPERADA_NA_FILA = { ana: 1, bruno: 2, carla: 3, gisele: 4, diego: 5, hugo: 6, elisa: 7, fabio: 8 };

/*
 * Cadastra as 5 creches e as 7 turmas da cola. Devolve {turmas: {'sol-MATERNAL': id, ...}}.
 * Cada chamada precisa dar certo; o chamador trata a exceção.
 */
async function cadastrarCrechesETurmasDaCola() {
  var turmas = {};
  for (var i = 0; i < MASSA.creches.length; i++) {
    var c = MASSA.creches[i];
    var creche = checar(await acaoCriarCreche({ nome: c.nome, capacidade: c.capacidade, rua: c.rua, lat: c.lat, lon: c.lon }), 'criar ' + c.nome);
    for (var j = 0; j < c.turmas.length; j++) {
      var t = c.turmas[j];
      var turma = checar(await acaoCriarTurma({ crecheId: creche.dados.crecheId, faixa: t.faixa, vagas: t.vagas }), 'criar turma de ' + c.nome);
      turmas[c.chave + '-' + t.faixa] = turma.dados.turmaId;
    }
  }
  return { turmas: turmas };
}

/*
 * Inscreve a família no cadastro inline. O CPF da cola é fixo; se este banco já o tem (rodada anterior),
 * repete com um CPF novo, para a demonstração nunca travar por CPF duplicado.
 */
async function inscreverFamiliaDaCola(chave, turmaId) {
  var f = MASSA.familias[chave];
  var dados = {
    modo: 'inline', turmaId: turmaId, renda: f.renda, vulneravel: f.vulneravel,
    responsavel: { nome: f.responsavel, cpf: f.cpf, telefone: f.telefone, email: f.email, rua: f.rua, numero: f.numero, lat: f.lat, lon: f.lon },
    crianca: { nome: f.crianca, nascimento: f.nascimento }
  };
  var resposta = await acaoInscrever(dados);
  if (resposta.status === 409) {
    dados.responsavel.cpf = '';
    resposta = await acaoInscrever(dados);
    resposta.cpfSubstituido = true;
  }
  return resposta;
}

// Inscreve as 8 famílias na ordem de chegada da cola e devolve {inscricoes: {chave: id}, cpfsSubstituidos: n}
async function inscreverTodasAsFamiliasDaCola(turmaId) {
  var inscricoes = {};
  var substituidos = 0;
  for (var i = 0; i < MASSA.ordemDeChegada.length; i++) {
    var chave = MASSA.ordemDeChegada[i];
    var r = checar(await inscreverFamiliaDaCola(chave, turmaId), 'inscrever ' + MASSA.familias[chave].responsavel);
    inscricoes[chave] = r.dados.inscricaoId;
    if (r.cpfSubstituido) { substituidos++; }
  }
  return { inscricoes: inscricoes, cpfsSubstituidos: substituidos };
}

// Botão da aba Roteiro: carrega as creches, as turmas e a fila de 8 famílias da cola, para o ensaio
async function carregarMassaDaCola() {
  var botao = $('r_btnMassa');
  var saida = $('r_massaRes');
  botao.disabled = true;
  saida.textContent = 'Carregando creches, turmas e famílias...';
  try {
    var base = await cadastrarCrechesETurmasDaCola();
    var turmaSol = base.turmas['sol-MATERNAL'];
    var fila = await inscreverTodasAsFamiliasDaCola(turmaSol);
    saida.textContent = '5 creches, 7 turmas e 8 famílias carregadas. A fila da Creche Sol (turma #' + turmaSol + ', 3 vagas) está pronta.' +
      (fila.cpfsSubstituidos ? ' ' + fila.cpfsSubstituidos + ' CPF(s) já existiam e foram trocados.' : '');
    avisar('Dados-base da cola carregados.', 'ok');
  } catch (erro) {
    saida.textContent = 'Não foi possível carregar tudo: ' + erro.message;
    avisar('Falha ao carregar os dados-base: ' + erro.message, 'erro');
  } finally {
    botao.disabled = false;
  }
}

function iniciarMassa() {
  $('r_btnMassa').onclick = carregarMassaDaCola;
}
