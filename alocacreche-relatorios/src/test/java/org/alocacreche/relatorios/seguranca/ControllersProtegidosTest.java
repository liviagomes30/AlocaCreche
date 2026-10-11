package org.alocacreche.relatorios.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.interceptor.SaInterceptor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

/**
 * Impede regressao de seguranca no MSRelatorios (AUTH-18 e AUTH-19): todo metodo de todo
 * {@code @RestController} ou {@code @Controller} de {@code org.alocacreche.relatorios} precisa de
 * {@code @SaCheckPermission} no formato {@code modulo:funcionalidade:operacao} ou de
 * {@code @SaIgnore}; {@code @SaIgnore} nunca convive com {@code @SaCheckPermission} e so e
 * permitido nos endpoints publicos aprovados; o {@link SaInterceptor} nunca pode declarar
 * caminho de exclusao. Controllers de teste aninhados nos ITs ficam fora da varredura.
 */
@DisplayName("Controllers do MSRelatorios protegidos e sem caminho de exclusao no Sa-Token")
class ControllersProtegidosTest {

    private static final String PACOTE_RAIZ = "org.alocacreche.relatorios";
    private static final Pattern FORMATO_PERMISSAO = Pattern.compile("[a-z]+:[a-z]+:[a-z]+");
    private static final Set<String> ENDPOINTS_PUBLICOS_APROVADOS = Set.of("PainelPublicoController#consultar");

    @Test
    @DisplayName("todo metodo de controller tem @SaCheckPermission ou @SaIgnore")
    void todoMetodoDeControllerTemPermissaoOuSaIgnore() {
        List<Class<?>> controllers = descobrirControllers();

        assertThat(controllers).isNotEmpty();
        assertThat(metodosSemProtecao(controllers)).isEmpty();
    }

    @Test
    @DisplayName("toda permissao declarada segue o formato modulo:funcionalidade:operacao")
    void todaPermissaoSegueOFormatoPadrao() {
        List<String> permissoes = descobrirControllers().stream()
                .flatMap(controller -> metodosDeEndpoint(controller).stream())
                .map(metodo -> AnnotatedElementUtils.findMergedAnnotation(metodo, SaCheckPermission.class))
                .filter(anotacao -> anotacao != null)
                .flatMap(anotacao -> Arrays.stream(anotacao.value()))
                .toList();

        assertThat(permissoes).isNotEmpty().allMatch(permissao -> FORMATO_PERMISSAO.matcher(permissao).matches());
    }

    @Test
    @DisplayName("a verificacao detecta metodo de controller sem nenhuma das duas anotacoes")
    void verificacaoDetectaMetodoSemProtecao() {
        List<String> semProtecao = metodosSemProtecao(List.of(ControllerFicticioSemProtecao.class));

        assertThat(semProtecao).containsExactly("ControllerFicticioSemProtecao#semAnotacao");
    }

    @Test
    @DisplayName("nenhum metodo dos controllers reais mistura @SaIgnore com @SaCheckPermission")
    void nenhumMetodoMisturaSaIgnoreComPermissao() {
        assertThat(metodosComSaIgnoreEPermissao(descobrirControllers())).isEmpty();
    }

    @Test
    @DisplayName("a verificacao detecta metodo com @SaIgnore e @SaCheckPermission ao mesmo tempo")
    void verificacaoDetectaSaIgnoreJuntoComPermissao() {
        List<String> conflitantes = metodosComSaIgnoreEPermissao(List.of(ControllerFicticioComAmbas.class));

        assertThat(conflitantes).containsExactly("ControllerFicticioComAmbas#ignoradoEProtegido");
    }

    @Test
    @DisplayName("so os endpoints publicos aprovados usam @SaIgnore")
    void soEndpointsPublicosAprovadosUsamSaIgnore() {
        List<Class<?>> controllers = descobrirControllers();

        assertThat(metodosAbertosNaoAprovados(controllers)).isEmpty();
        assertThat(metodosAbertos(controllers)).containsExactlyInAnyOrderElementsOf(ENDPOINTS_PUBLICOS_APROVADOS);
    }

    @Test
    @DisplayName("a verificacao detecta @SaIgnore fora da lista de endpoints publicos aprovados")
    void verificacaoDetectaSaIgnoreForaDaListaAprovada() {
        List<String> naoAprovados = metodosAbertosNaoAprovados(List.of(ControllerFicticioAberto.class));

        assertThat(naoAprovados).containsExactly("ControllerFicticioAberto#abertoSemAprovacao");
    }

    @Test
    @DisplayName("a varredura inclui @Controller (nao so @RestController) e exclui controllers de teste por padrao")
    void varreduraIncluiControllerEExcluiControllersDeTeste() {
        String pacoteDosITs = PACOTE_RAIZ + ".seguranca";

        List<String> semTestes = nomesSimples(descobrirControllers(pacoteDosITs, false));
        List<String> comTestes = nomesSimples(descobrirControllers(pacoteDosITs, true));

        assertThat(semTestes).doesNotContain("ControllerDeTeste");
        assertThat(comTestes).contains("ControllerDeTeste");
    }

    @Test
    @DisplayName("o SaInterceptor e registrado sem nenhum caminho de exclusao")
    void interceptorNaoDeclaraCaminhoDeExclusao() {
        InterceptorRegistry registry = new InterceptorRegistry();

        new SegurancaConfig(true).addInterceptors(registry);

        List<?> registros = (List<?>) ReflectionTestUtils.getField(registry, "registrations");
        assertThat(registros).hasSize(1);
        Object interceptor = ReflectionTestUtils.getField(registros.get(0), "interceptor");
        assertThat(interceptor).isInstanceOf(SaInterceptor.class);
        Object excluidos = ReflectionTestUtils.getField(registros.get(0), "excludePatterns");
        assertThat(excluidos == null || ((List<?>) excluidos).isEmpty()).isTrue();
    }

    private static List<Class<?>> descobrirControllers() {
        return descobrirControllers(PACOTE_RAIZ, false);
    }

    private static List<Class<?>> descobrirControllers(String pacote, boolean incluirClassesDeTeste) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        return scanner.findCandidateComponents(pacote).stream()
                .map(BeanDefinition::getBeanClassName)
                .map(nome -> ClassUtils.resolveClassName(nome, ControllersProtegidosTest.class.getClassLoader()))
                .<Class<?>>map(classe -> classe)
                .filter(classe -> incluirClassesDeTeste || !estaEmClassesDeTeste(classe))
                .toList();
    }

    private static boolean estaEmClassesDeTeste(Class<?> classe) {
        String origem = classe.getProtectionDomain().getCodeSource().getLocation().getPath();
        return origem.contains("test-classes");
    }

    private static List<String> nomesSimples(List<Class<?>> classes) {
        return classes.stream().map(Class::getSimpleName).toList();
    }

    private static List<String> metodosSemProtecao(List<Class<?>> controllers) {
        return controllers.stream()
                .flatMap(controller -> metodosDeEndpoint(controller).stream())
                .filter(metodo -> !AnnotatedElementUtils.hasAnnotation(metodo, SaCheckPermission.class)
                        && !AnnotatedElementUtils.hasAnnotation(metodo, SaIgnore.class))
                .map(ControllersProtegidosTest::identificar)
                .toList();
    }

    private static List<String> metodosComSaIgnoreEPermissao(List<Class<?>> controllers) {
        return controllers.stream()
                .flatMap(controller -> metodosDeEndpoint(controller).stream())
                .filter(metodo -> AnnotatedElementUtils.hasAnnotation(metodo, SaIgnore.class)
                        && AnnotatedElementUtils.hasAnnotation(metodo, SaCheckPermission.class))
                .map(ControllersProtegidosTest::identificar)
                .toList();
    }

    private static List<String> metodosAbertos(List<Class<?>> controllers) {
        return controllers.stream()
                .flatMap(controller -> metodosDeEndpoint(controller).stream())
                .filter(metodo -> AnnotatedElementUtils.hasAnnotation(metodo, SaIgnore.class))
                .map(ControllersProtegidosTest::identificar)
                .toList();
    }

    private static List<String> metodosAbertosNaoAprovados(List<Class<?>> controllers) {
        return metodosAbertos(controllers).stream()
                .filter(identificador -> !ENDPOINTS_PUBLICOS_APROVADOS.contains(identificador))
                .toList();
    }

    private static String identificar(Method metodo) {
        return metodo.getDeclaringClass().getSimpleName() + "#" + metodo.getName();
    }

    private static List<Method> metodosDeEndpoint(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(metodo -> AnnotatedElementUtils.hasAnnotation(metodo, RequestMapping.class))
                .toList();
    }

    static class ControllerFicticioSemProtecao {

        @GetMapping("/ficticio")
        public String semAnotacao() {
            return "ok";
        }
    }

    static class ControllerFicticioComAmbas {

        @SaIgnore
        @SaCheckPermission("relatorios:ocupacao:list")
        @GetMapping("/ficticio-ambas")
        public String ignoradoEProtegido() {
            return "ok";
        }
    }

    static class ControllerFicticioAberto {

        @SaIgnore
        @GetMapping("/ficticio-aberto")
        public String abertoSemAprovacao() {
            return "ok";
        }
    }
}
