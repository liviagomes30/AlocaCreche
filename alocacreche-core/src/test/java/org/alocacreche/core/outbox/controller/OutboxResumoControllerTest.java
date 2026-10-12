package org.alocacreche.core.outbox.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.annotation.SaCheckPermission;
import java.util.Optional;
import org.alocacreche.core.comum.seguranca.domain.PerfilUsuario;
import org.alocacreche.core.outbox.repository.OutboxEventRepository;
import org.alocacreche.core.outbox.service.OutboxPublicadorControle;
import org.alocacreche.core.outbox.service.OutboxPublicadorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.http.HttpStatus;

@DisplayName("OutboxResumoController")
class OutboxResumoControllerTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final OutboxPublicadorService publicador = mock(OutboxPublicadorService.class);
    private final AmqpAdmin amqpAdmin = mock(AmqpAdmin.class);

    private OutboxResumoController controller(OutboxPublicadorControle controle) {
        return new OutboxResumoController(repository, controle, publicador, amqpAdmin);
    }

    @Test
    @DisplayName("devolve a contagem de cada status, o estado do publicador e as mensagens nas DLQs")
    void devolveContagemEEstado() {
        when(repository.countByStatus("PENDENTE")).thenReturn(3L);
        when(repository.countByStatus("PROCESSADO")).thenReturn(40L);
        when(repository.countByStatus("ERRO")).thenReturn(1L);
        when(amqpAdmin.getQueueInfo("relatorios.ocupacao.dlq")).thenReturn(new QueueInformation("a", 2, 0));
        when(amqpAdmin.getQueueInfo("relatorios.filaespera.dlq")).thenReturn(new QueueInformation("b", 1, 0));
        when(amqpAdmin.getQueueInfo("relatorios.matricula.dlq")).thenReturn(null);

        OutboxResumoController.ResumoOutbox resumo = controller(new OutboxPublicadorControle(true)).resumo();

        assertThat(resumo.pendentes()).isEqualTo(3);
        assertThat(resumo.processados()).isEqualTo(40);
        assertThat(resumo.erros()).isEqualTo(1);
        assertThat(resumo.publicadorPausado()).isFalse();
        assertThat(resumo.controleDemo()).isTrue();
        assertThat(resumo.brokerDisponivel()).isTrue();
        assertThat(resumo.dlqMensagens()).isEqualTo(3L);
    }

    @Test
    @DisplayName("com o broker fora do ar, informa brokerDisponivel=false e dlqMensagens nulo")
    void brokerForaDoAr() {
        when(amqpAdmin.getQueueInfo(anyString())).thenThrow(new AmqpConnectException(new RuntimeException("sem conexao")));

        OutboxResumoController.ResumoOutbox resumo = controller(new OutboxPublicadorControle(true)).resumo();

        assertThat(resumo.brokerDisponivel()).isFalse();
        assertThat(resumo.dlqMensagens()).isNull();
    }

    @Test
    @DisplayName("pausar e retomar alteram o estado do publicador quando o controle de demonstracao esta ligado")
    void pausarERetomar() {
        OutboxPublicadorControle controle = new OutboxPublicadorControle(true);
        OutboxResumoController controller = controller(controle);

        assertThat(controller.pausar().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controle.pausado()).isTrue();
        assertThat(controller.retomar().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controle.pausado()).isFalse();
    }

    @Test
    @DisplayName("com o controle de demonstracao desligado, pausar e reenviar respondem 403 e nada muda")
    void controleDesligadoRecusa() {
        OutboxPublicadorControle controle = new OutboxPublicadorControle(false);
        OutboxResumoController controller = controller(controle);

        assertThat(controller.pausar().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.reenviarAntigo(1L).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controle.pausado()).isFalse();
    }

    @Test
    @DisplayName("reenviar-antigo devolve o eventId reenviado, ou 404 quando a turma nao tem evento")
    void reenviarAntigo() {
        when(publicador.reenviarEventoAntigoDaTurma(5L)).thenReturn(Optional.of(12L));
        when(publicador.reenviarEventoAntigoDaTurma(6L)).thenReturn(Optional.empty());
        OutboxResumoController controller = controller(new OutboxPublicadorControle(true));

        assertThat(controller.reenviarAntigo(5L).getBody()).containsEntry("eventId", 12L);
        assertThat(controller.reenviarAntigo(6L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("as permissoes da Outbox so existem para o ADMIN (curinga)")
    void permissoesRestritasAoAdmin() throws Exception {
        String listar = OutboxResumoController.class.getMethod("resumo").getAnnotation(SaCheckPermission.class).value()[0];
        String controlar = OutboxResumoController.class.getMethod("pausar").getAnnotation(SaCheckPermission.class).value()[0];

        assertThat(listar).isEqualTo("notificacao:outbox:list");
        assertThat(controlar).isEqualTo("notificacao:outbox:status");
        assertThat(PerfilUsuario.GESTOR.permissoes()).doesNotContain(listar, controlar);
        assertThat(PerfilUsuario.ATENDENTE.permissoes()).doesNotContain(listar, controlar);
    }
}
