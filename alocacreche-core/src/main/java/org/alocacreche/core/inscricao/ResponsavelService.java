package org.alocacreche.core.inscricao;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.alocacreche.core.comum.excecao.ConflitoException;
import org.alocacreche.core.comum.seguranca.SessaoTenantResolver;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cadastro de {@link Responsavel}. A regra de CPF unico vive aqui e e reaproveitada pelo cadastro
 * inline da inscricao.
 */
@Service
@RequiredArgsConstructor
public class ResponsavelService {

    private static final String MENSAGEM_CPF_DUPLICADO = "CPF ja cadastrado";

    private final ResponsavelRepository responsavelRepository;
    private final SessaoTenantResolver sessaoTenantResolver;
    private final EntityManager entityManager;

    /** Cadastro pelo endpoint: o municipio vem do token e o tenant e aplicado antes de qualquer leitura. */
    @Transactional
    public Responsavel cadastrar(ResponsavelDTO dto) {
        Long municipioId = sessaoTenantResolver.municipioIdAtual();
        TenantContext.aplicar(entityManager, municipioId);
        return persistir(municipioId, dto);
    }

    /**
     * Cadastro dentro de uma transacao que ja resolveu o municipio e aplicou o tenant (usado pelo
     * cadastro inline da inscricao). Exige transacao aberta.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Responsavel cadastrarNoMunicipio(Long municipioId, ResponsavelDTO dto) {
        return persistir(municipioId, dto);
    }

    private Responsavel persistir(Long municipioId, ResponsavelDTO dto) {
        exigirCpfLivre(dto.cpf());

        Responsavel responsavel = new Responsavel();
        responsavel.setMunicipioId(municipioId);
        responsavel.setNome(dto.nome());
        responsavel.setCpf(dto.cpf());
        responsavel.setTelefone(dto.telefone());
        responsavel.setEmail(dto.email());
        responsavel.setEndereco(dto.endereco());

        try {
            return responsavelRepository.saveAndFlush(responsavel);
        } catch (DataIntegrityViolationException excecao) {
            // O CPF e unico no banco inteiro: a RLS esconde o responsavel de outro municipio do
            // findByCpf, entao a violacao so aparece no flush.
            throw new ConflitoException(MENSAGEM_CPF_DUPLICADO);
        }
    }

    private void exigirCpfLivre(String cpf) {
        if (responsavelRepository.findByCpf(cpf).isPresent()) {
            throw new ConflitoException(MENSAGEM_CPF_DUPLICADO);
        }
    }
}
