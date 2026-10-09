package org.alocacreche.relatorios.seguranca.service;

import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MunicipioDoToken {

    static final String CLAIM_MUNICIPIO = "municipioId";

    public void exigir(Long municipioIdPedido) {
        Long municipioDoToken = lerMunicipioDoToken();
        if (municipioIdPedido == null || municipioDoToken == null || !municipioDoToken.equals(municipioIdPedido)) {
            throw new NotPermissionException(CLAIM_MUNICIPIO);
        }
    }

    private Long lerMunicipioDoToken() {
        try {
            Object claim = StpUtil.getExtra(CLAIM_MUNICIPIO);
            return claim instanceof Number numero ? numero.longValue() : null;
        } catch (SaTokenException excecao) {
            log.debug("Claim {} do token indisponivel: {}", CLAIM_MUNICIPIO, excecao.getClass().getSimpleName());
            return null;
        }
    }
}
