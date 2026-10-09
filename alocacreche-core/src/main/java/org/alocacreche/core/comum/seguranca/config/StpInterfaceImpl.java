package org.alocacreche.core.comum.seguranca.config;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import java.util.Collection;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StpInterfaceImpl implements StpInterface {

    static final String CLAIM_PERMISSOES = "perms";

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        try {
            if (!String.valueOf(loginId).equals(String.valueOf(StpUtil.getLoginIdDefaultNull()))) {
                return List.of();
            }
            Object permissoes = StpUtil.getExtra(CLAIM_PERMISSOES);
            if (permissoes instanceof Collection<?> colecao) {
                return colecao.stream().map(String::valueOf).toList();
            }
        } catch (SaTokenException excecao) {
            log.debug("Permissoes do token indisponiveis: {}", excecao.getClass().getSimpleName());
        }
        return List.of();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return List.of();
    }
}
