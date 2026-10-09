package org.alocacreche.relatorios.seguranca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.dev33.satoken.exception.NotPermissionException;
import java.util.List;
import org.alocacreche.relatorios.seguranca.ContextoSaTokenExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

/**
 * Prova que o municipio pedido so passa se for o da claim {@code municipioId} do token
 * (AUTH-17): igual passa, diferente ou ausente vira {@code NotPermissionException} (403).
 */
@ExtendWith(ContextoSaTokenExtension.class)
@DisplayName("MunicipioDoToken confere o municipio pedido contra a claim do token")
class MunicipioDoTokenTest {

    private final MunicipioDoToken municipioDoToken = new MunicipioDoToken();

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private Level nivelAnterior;

    @BeforeEach
    void capturarLog() {
        logger = (Logger) LoggerFactory.getLogger(MunicipioDoToken.class);
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
    @DisplayName("municipio igual ao do token passa")
    void municipioIgualAoDoTokenPassa() {
        ContextoSaTokenExtension.requisicaoComToken(
                ContextoSaTokenExtension.emitirToken(7L, List.of("relatorios:ocupacao:list")));

        assertThatCode(() -> municipioDoToken.exigir(7L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("municipio diferente do token lanca NotPermissionException")
    void municipioDiferenteLancaNotPermissionException() {
        ContextoSaTokenExtension.requisicaoComToken(
                ContextoSaTokenExtension.emitirToken(7L, List.of("relatorios:ocupacao:list")));

        assertThatThrownBy(() -> municipioDoToken.exigir(8L)).isInstanceOf(NotPermissionException.class);
    }

    @Test
    @DisplayName("claim municipioId ausente lanca NotPermissionException")
    void claimAusenteLancaNotPermissionException() {
        ContextoSaTokenExtension.requisicaoComToken(ContextoSaTokenExtension.emitirTokenSemClaims());

        assertThatThrownBy(() -> municipioDoToken.exigir(7L)).isInstanceOf(NotPermissionException.class);
    }

    @Test
    @DisplayName("municipio pedido ausente lanca NotPermissionException")
    void municipioPedidoAusenteLancaNotPermissionException() {
        ContextoSaTokenExtension.requisicaoComToken(
                ContextoSaTokenExtension.emitirToken(7L, List.of("relatorios:ocupacao:list")));

        assertThatThrownBy(() -> municipioDoToken.exigir(null)).isInstanceOf(NotPermissionException.class);
    }

    @Test
    @DisplayName("sem token na requisicao lanca NotPermissionException em vez de passar")
    void semTokenLancaNotPermissionException() {
        assertThatThrownBy(() -> municipioDoToken.exigir(7L)).isInstanceOf(NotPermissionException.class);
    }

    @Test
    @DisplayName("token ilegivel: registra em debug o nome da excecao, sem o valor do token, e continua negando")
    void tokenIlegivelRegistraACausaSemExporOToken() {
        String tokenIlegivel = "isto-nao-e-um-jwt-segredo-123";
        ContextoSaTokenExtension.requisicaoComToken(tokenIlegivel);

        assertThatThrownBy(() -> municipioDoToken.exigir(7L)).isInstanceOf(NotPermissionException.class);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent evento = appender.list.get(0);
        assertThat(evento.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(evento.getFormattedMessage()).contains("Exception").doesNotContain(tokenIlegivel);
    }
}
