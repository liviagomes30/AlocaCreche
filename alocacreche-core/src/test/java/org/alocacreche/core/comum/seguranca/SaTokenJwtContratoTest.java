package org.alocacreche.core.comum.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.jwt.SaJwtUtil;
import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import cn.dev33.satoken.strategy.SaStrategy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Prova o contrato do sa-token-jwt 1.44.0 do qual o login depende: formato das claims
 * {@code municipioId} e {@code perms}, unidade do timeout e o curinga {@code *} de permissao.
 */
@DisplayName("Contrato do Sa-Token JWT (claims, expiracao e curinga)")
class SaTokenJwtContratoTest {

    private static final String SEGREDO = "segredo-de-teste-do-contrato-jwt-0123456789";
    private static final String SEGREDO_PADRAO = "troque-este-segredo-antes-de-qualquer-deploy";

    /**
     * JWT fixo emitido com o segredo padrao do core (municipioId=1, perms relatorios:ocupacao:list,
     * expiracao no seculo 22). O mesmo valor e usado no SegurancaConfigIT do MSRelatorios.
     */
    private static final String TOKEN_DOURADO =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJsb2dpblR5cGUiOiJsb2dpbiIsImxvZ2luSWQiOjEsImRldmljZVR5cGUiOiJkZWZhdWx0IiwiZWZmIjo0MjkxMjEwNjY2NjY5LCJyblN0ciI6ImlYRmRYQ2hWQldFUG41ZjgxZm5OY3NoUE9HRlNyS2VlIiwicGVybXMiOlsicmVsYXRvcmlvczpvY3VwYWNhbzpsaXN0Il0sIm11bmljaXBpb0lkIjoxfQ.UlvWX7wmwTaDYM2zIEDUQW_N5Z1b0mPjLaMu6j0t_qk";

    private SaTokenConfig configAnterior;
    private StpLogicJwtForStateless stpLogic;

    @BeforeEach
    void setUp() {
        configAnterior = SaManager.getConfig();
        SaManager.setConfig(new SaTokenConfig().setJwtSecretKey(SEGREDO).setIsPrint(false));
        stpLogic = new StpLogicJwtForStateless();
    }

    @AfterEach
    void tearDown() {
        SaManager.setConfig(configAnterior);
    }

    @Test
    @DisplayName("as claims municipioId (numero) e perms (lista de textos) voltam do token emitido")
    void claimsMunicipioIdEPermsVoltamDoToken() {
        String token = stpLogic.createTokenValue(
                42L, "default", 28800,
                Map.of("municipioId", 7L, "perms", List.of("relatorios:ocupacao:list", "relatorios:fila:list")));

        Object municipioId = stpLogic.getExtra(token, "municipioId");
        Object perms = stpLogic.getExtra(token, "perms");

        assertThat(municipioId).isInstanceOf(Number.class);
        assertThat(((Number) municipioId).longValue()).isEqualTo(7L);
        assertThat(perms).isInstanceOf(List.class);
        assertThat((List<?>) perms).map(String::valueOf)
                .containsExactly("relatorios:ocupacao:list", "relatorios:fila:list");
    }

    @Test
    @DisplayName("o token fixo e lido com o segredo padrao do core e traz municipioId e perms")
    void tokenFixoELidoComOSegredoPadraoDoCore() {
        SaManager.setConfig(new SaTokenConfig().setJwtSecretKey(SEGREDO_PADRAO).setIsPrint(false));

        Object municipioId = stpLogic.getExtra(TOKEN_DOURADO, "municipioId");
        Object perms = stpLogic.getExtra(TOKEN_DOURADO, "perms");

        assertThat(((Number) municipioId).longValue()).isEqualTo(1L);
        assertThat((List<?>) perms).map(String::valueOf).containsExactly("relatorios:ocupacao:list");
    }

    @Test
    @DisplayName("o timeout e em segundos: 28800 gera expiracao 8 horas a frente")
    void timeoutEmSegundosGeraExpiracaoOitoHorasAFrente() {
        long antes = System.currentTimeMillis();
        String token = stpLogic.createTokenValue(42L, "default", 28800, Map.of("municipioId", 1L));
        long depois = System.currentTimeMillis();

        long eff = SaJwtUtil.getPayloads(token, stpLogic.getLoginType(), SEGREDO).getLong(SaJwtUtil.EFF);

        assertThat(eff).isBetween(antes + 28_800_000L, depois + 28_800_000L);
    }

    @Test
    @DisplayName("um token ja expirado e rejeitado na leitura")
    void tokenExpiradoERejeitado() {
        String token = SaJwtUtil.createToken(
                Map.of(
                        SaJwtUtil.LOGIN_TYPE, stpLogic.getLoginType(),
                        SaJwtUtil.LOGIN_ID, 42L,
                        SaJwtUtil.EFF, System.currentTimeMillis() - 1_000L,
                        "municipioId", 1L),
                SEGREDO);

        assertThatThrownBy(() -> SaJwtUtil.getPayloads(token, stpLogic.getLoginType(), SEGREDO))
                .isInstanceOf(SaTokenException.class);
    }

    @Test
    @DisplayName("um token assinado com outro segredo e rejeitado")
    void tokenComOutroSegredoERejeitado() {
        String token = stpLogic.createTokenValue(42L, "default", 28800, Map.of("municipioId", 1L));

        assertThatThrownBy(() -> SaJwtUtil.getPayloads(token, stpLogic.getLoginType(), "outro-segredo-qualquer-0123456789"))
                .isInstanceOf(SaTokenException.class);
    }

    @Test
    @DisplayName("a permissao * casa com qualquer permissao exigida")
    void curingaCasaComQualquerPermissao() {
        assertThat(SaStrategy.instance.hasElement.apply(List.of("*"), "relatorios:ocupacao:list")).isTrue();
        assertThat(SaStrategy.instance.hasElement.apply(List.of("*"), "inscricao:inscricao:add")).isTrue();
    }

    @Test
    @DisplayName("sem o curinga, uma permissao ausente da lista nao casa")
    void permissaoAusenteNaoCasa() {
        assertThat(SaStrategy.instance.hasElement.apply(
                        List.of("relatorios:fila:list"), "relatorios:ocupacao:list"))
                .isFalse();
    }
}
