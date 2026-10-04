package org.alocacreche.core.comum.seguranca;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import java.util.List;

/**
 * Suporte de teste: le a claim {@code perms} do JWT da requisicao para exercitar o
 * {@code @SaCheckPermission}. O {@code StpInterface} de producao do core pertence a feature
 * nucleo-bootstrap-comum (fora do escopo de autenticacao-login).
 */
class StpInterfaceDeTeste implements StpInterface {

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Object perms = StpUtil.getExtra("perms");
        if (!(perms instanceof List<?> lista)) {
            return List.of();
        }
        return lista.stream().map(String::valueOf).toList();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return List.of();
    }
}
