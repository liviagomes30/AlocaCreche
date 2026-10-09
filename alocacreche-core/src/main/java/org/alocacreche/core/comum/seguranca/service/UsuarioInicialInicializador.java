package org.alocacreche.core.comum.seguranca.service;

import cn.dev33.satoken.secure.BCrypt;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alocacreche.core.comum.seguranca.config.UsuarioInicialProperties;
import org.alocacreche.core.comum.seguranca.domain.PerfilUsuario;
import org.alocacreche.core.comum.seguranca.domain.Usuario;
import org.alocacreche.core.comum.seguranca.repository.UsuarioRepository;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.notificacao.MunicipioRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Component
@EnableConfigurationProperties(UsuarioInicialProperties.class)
@RequiredArgsConstructor
public class UsuarioInicialInicializador implements ApplicationRunner {

    private static final String NOME_PADRAO = "Administrador inicial";
    private static final int MAXIMO_BYTES_SENHA = 72;

    private final UsuarioInicialProperties propriedades;
    private final MunicipioRepository municipioRepository;
    private final UsuarioRepository usuarioRepository;
    private final EntityManager entityManager;
    private final PlatformTransactionManager transactionManager;

    @Override
    public void run(ApplicationArguments args) {
        if (!propriedades.estaConfigurado()) {
            return;
        }
        validarTamanhoDaSenha();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> criarSeNecessario());
        } catch (DataIntegrityViolationException e) {
            log.info("Usuario ADMIN inicial ja criado por outra instancia no municipio {}", propriedades.municipioId());
        }
    }

    private void validarTamanhoDaSenha() {
        if (propriedades.senha().getBytes(StandardCharsets.UTF_8).length > MAXIMO_BYTES_SENHA) {
            throw new IllegalStateException(
                    "A senha do usuario inicial excede o limite de " + MAXIMO_BYTES_SENHA + " bytes em UTF-8 do BCrypt");
        }
    }

    private void criarSeNecessario() {
        Long municipioId = propriedades.municipioId();
        if (!municipioRepository.existsById(municipioId)) {
            log.warn("Usuario inicial nao criado: municipio {} inexistente", municipioId);
            return;
        }
        TenantContext.aplicar(entityManager, municipioId);

        String email = propriedades.email().trim().toLowerCase(Locale.ROOT);
        if (usuarioRepository.findByEmail(email).isPresent()) {
            return;
        }
        Usuario admin = new Usuario();
        admin.setMunicipioId(municipioId);
        admin.setEmail(email);
        admin.setNome(NOME_PADRAO);
        admin.setSenhaHash(BCrypt.hashpw(propriedades.senha()));
        admin.setPerfil(PerfilUsuario.ADMIN);
        admin.setAtivo(true);
        usuarioRepository.saveAndFlush(admin);
        log.info("Usuario ADMIN inicial criado no municipio {}", municipioId);
    }
}
