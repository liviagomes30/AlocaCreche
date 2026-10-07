package org.alocacreche.core.inscricao;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.alocacreche.core.comum.seguranca.SessaoTenantResolver;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ResponsavelService {

    private final ResponsavelRepository responsavelRepository;
    private final SessaoTenantResolver sessaoTenantResolver;
    private final EntityManager entityManager;

    @Transactional
    public Responsavel cadastrar(ResponsavelDTO dto) {
        Long municipioId = sessaoTenantResolver.municipioIdAtual();

        TenantContext.aplicar(entityManager, municipioId);

        Responsavel responsavel = new Responsavel();
        responsavel.setMunicipioId(municipioId);
        responsavel.setNome(dto.nome());
        responsavel.setCpf(dto.cpf());
        responsavel.setTelefone(dto.telefone());
        responsavel.setEmail(dto.email());

        return responsavelRepository.save(responsavel);
    }
}