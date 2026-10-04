package org.alocacreche.core.comum.seguranca;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.jwt.SaJwtUtil;
import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import cn.dev33.satoken.secure.BCrypt;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.notificacao.MunicipioRepository;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@RequiredArgsConstructor
public class AutenticacaoServiceImpl implements AutenticacaoService {

    private static final String DISPOSITIVO_PADRAO = "default";

    private final String hashFicticio = BCrypt.hashpw("senha-ficticia-para-tempo-constante");

    private final UsuarioRepository usuarioRepository;
    private final MunicipioRepository municipioRepository;
    private final EntityManager entityManager;
    private final StpLogicJwtForStateless stpLogic;

    @Override
    @Transactional(readOnly = true)
    public LoginResponse autenticar(LoginRequest request) {
        Long municipioId = request.municipioId();
        TenantContext.aplicar(entityManager, municipioId);

        Optional<Usuario> usuario = buscarUsuario(municipioId, normalizar(request.email()));
        String hashParaConferir = usuario.map(Usuario::getSenhaHash).orElse(hashFicticio);
        boolean senhaConfere = BCrypt.checkpw(request.senha(), hashParaConferir);

        if (usuario.isEmpty() || !usuario.get().isAtivo() || !senhaConfere) {
            log.warn("Falha de login no municipio {}: credenciais invalidas", municipioId);
            throw new CredenciaisInvalidasException();
        }
        return emitirToken(usuario.get(), municipioId);
    }

    private Optional<Usuario> buscarUsuario(Long municipioId, String email) {
        if (!municipioRepository.existsById(municipioId)) {
            return Optional.empty();
        }
        return usuarioRepository.findByEmail(email);
    }

    private LoginResponse emitirToken(Usuario usuario, Long municipioId) {
        long validadeEmSegundos = SaManager.getConfig().getTimeout();
        String token = stpLogic.createTokenValue(
                usuario.getId(),
                DISPOSITIVO_PADRAO,
                validadeEmSegundos,
                Map.of("municipioId", municipioId, "perms", usuario.getPerfil().permissoes()));
        return new LoginResponse(token, LoginResponse.TIPO_BEARER, expiracaoDo(token));
    }

    private Instant expiracaoDo(String token) {
        long expiracaoEmMilissegundos = SaJwtUtil
                .getPayloads(token, stpLogic.getLoginType(), SaManager.getConfig().getJwtSecretKey())
                .getLong(SaJwtUtil.EFF);
        return Instant.ofEpochMilli(expiracaoEmMilissegundos);
    }

    private static String normalizar(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
