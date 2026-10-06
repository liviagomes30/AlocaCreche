package org.alocacreche.core.comum.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.dev33.satoken.exception.NotPermissionException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

/**
 * Prova que o municipio da sessao vem da claim {@code municipioId} do JWT e que a ausencia dela
 * vira {@code NotPermissionException}, traduzida em HTTP 403 (PROD-18).
 */
@ExtendWith(ContextoSaTokenExtension.class)
@DisplayName("SessaoTenantResolver resolve o municipio da claim do JWT")
class SessaoTenantResolverTest {

    private final SessaoTenantResolver resolver = new SessaoTenantResolver();

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private Level nivelAnterior;

    @BeforeEach
    void capturarLog() {
        logger = (Logger) LoggerFactory.getLogger(SessaoTenantResolver.class);
        nivelAnterior = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void restaurarLog() {
        logger.detachAppender(appender);
        logger.setLevel(nivelAnterior);
    }

    @Test
    @DisplayName("devolve o municipioId da claim como Long")
    void devolveOMunicipioIdDaClaim() {
        ContextoSaTokenExtension.requisicaoComToken(
                ContextoSaTokenExtension.emitirToken(7L, List.of("*")));

        Long municipioId = resolver.municipioIdAtual();

        assertThat(municipioId).isEqualTo(7L);
    }

    @Test
    @DisplayName("claim municipioId ausente lanca NotPermissionException")
    void claimAusenteLancaNotPermissionException() {
        ContextoSaTokenExtension.requisicaoComToken(ContextoSaTokenExtension.emitirTokenSemClaims());

        assertThatThrownBy(resolver::municipioIdAtual).isInstanceOf(NotPermissionException.class);
    }

    @Test
    @DisplayName("sem token na requisicao lanca NotPermissionException")
    void semTokenLancaNotPermissionException() {
        assertThatThrownBy(resolver::municipioIdAtual).isInstanceOf(NotPermissionException.class);
    }

    @Test
    @DisplayName("token malformado lanca NotPermissionException")
    void tokenMalformadoLancaNotPermissionException() {
        ContextoSaTokenExtension.requisicaoComToken("isto-nao-e-um-jwt");

        assertThatThrownBy(resolver::municipioIdAtual).isInstanceOf(NotPermissionException.class);
    }

    @Test
    @DisplayName("token ilegivel: registra em debug o nome da excecao, sem o valor do token, e continua negando")
    void tokenIlegivelRegistraACausaSemExporOToken() {
        String tokenIlegivel = "isto-nao-e-um-jwt-segredo-123";
        ContextoSaTokenExtension.requisicaoComToken(tokenIlegivel);

        assertThatThrownBy(() -> resolver.municipioIdAtual()).isInstanceOf(NotPermissionException.class);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent evento = appender.list.get(0);
        assertThat(evento.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(evento.getFormattedMessage()).contains("Exception").doesNotContain(tokenIlegivel);
    }
}
