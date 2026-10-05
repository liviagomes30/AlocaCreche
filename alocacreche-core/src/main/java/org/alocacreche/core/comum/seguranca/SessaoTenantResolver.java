package org.alocacreche.core.comum.seguranca;

import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Component;

@Component
public class SessaoTenantResolver {

    static final String CLAIM_MUNICIPIO = "municipioId";

    public Long municipioIdAtual() {
        Long municipioId = lerMunicipioDoToken();
        if (municipioId == null) {
            throw new NotPermissionException(CLAIM_MUNICIPIO);
        }
        return municipioId;
    }

    private Long lerMunicipioDoToken() {
        try {
            Object claim = StpUtil.getExtra(CLAIM_MUNICIPIO);
            return claim instanceof Number numero ? numero.longValue() : null;
        } catch (SaTokenException excecao) {
            return null;
        }
    }
}
