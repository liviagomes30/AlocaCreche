package org.alocacreche.core.inscricao;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.alocacreche.core.comum.seguranca.SessaoTenantResolver;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CriancaService {

    private final CriancaRepository criancaRepository;
    private final ResponsavelRepository responsavelRepository;
    private final SessaoTenantResolver sessaoTenantResolver;
    private final EntityManager entityManager;

    @Transactional
    public Crianca cadastrar(CriancaDTO dto) {

        Long municipioId = sessaoTenantResolver.municipioIdAtual();

        TenantContext.aplicar(entityManager, municipioId);

        Responsavel responsavel = responsavelRepository.findById(dto.responsavelId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Responsável não encontrado: " + dto.responsavelId()));

        Crianca crianca = new Crianca();

        crianca.setMunicipioId(municipioId);
        crianca.setResponsavel(responsavel);
        crianca.setNome(dto.nome());
        crianca.setDataNascimento(dto.dataNascimento());

        return criancaRepository.save(crianca);
    }
}