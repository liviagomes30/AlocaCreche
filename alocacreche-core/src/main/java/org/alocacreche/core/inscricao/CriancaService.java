package org.alocacreche.core.inscricao;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.alocacreche.core.comum.excecao.RecursoNaoEncontradoException;
import org.alocacreche.core.comum.seguranca.SessaoTenantResolver;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Cadastro de {@link Crianca}, reaproveitado pelo cadastro inline da inscricao. */
@Service
@RequiredArgsConstructor
public class CriancaService {

    private final CriancaRepository criancaRepository;
    private final ResponsavelRepository responsavelRepository;
    private final SessaoTenantResolver sessaoTenantResolver;
    private final EntityManager entityManager;

    /**
     * Cadastro pelo endpoint: o municipio vem do token e o tenant e aplicado antes de qualquer
     * leitura. Responsavel de outro municipio fica invisivel pela RLS e responde 404.
     */
    @Transactional
    public Crianca cadastrar(Long responsavelId, CriancaDTO dados) {
        Long municipioId = sessaoTenantResolver.municipioIdAtual();
        TenantContext.aplicar(entityManager, municipioId);

        Responsavel responsavel = responsavelRepository.findById(responsavelId)
                .orElseThrow(() -> new RecursoNaoEncontradoException(
                        "Responsável não encontrado: " + responsavelId));
        return persistir(municipioId, responsavel, dados);
    }

    /**
     * Cadastro dentro de uma transacao que ja resolveu o municipio e aplicou o tenant (usado pelo
     * cadastro inline da inscricao). Exige transacao aberta.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Crianca cadastrarNoMunicipio(Long municipioId, Responsavel responsavel, CriancaDTO dados) {
        return persistir(municipioId, responsavel, dados);
    }

    private Crianca persistir(Long municipioId, Responsavel responsavel, CriancaDTO dados) {
        Crianca crianca = new Crianca();
        crianca.setMunicipioId(municipioId);
        crianca.setNome(dados.nome());
        crianca.setDataNascimento(dados.dataNascimento());
        responsavel.adicionarCrianca(crianca);
        return criancaRepository.saveAndFlush(crianca);
    }
}
