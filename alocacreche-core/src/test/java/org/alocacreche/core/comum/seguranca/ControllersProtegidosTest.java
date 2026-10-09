package org.alocacreche.core.comum.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.alocacreche.core.comum.seguranca.controller.AuthController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Impede regressao de seguranca no core: todo metodo de todo {@code @RestController} ou
 * {@code @Controller} de {@code org.alocacreche.core} precisa de {@code @SaCheckPermission} no
 * formato {@code modulo:funcionalidade:operacao} ou de {@code @SaIgnore}; {@code @SaIgnore} nunca
 * convive com {@code @SaCheckPermission} e so e permitido nos endpoints publicos aprovados (o
 * login). Controllers de teste aninhados nos ITs ficam fora da varredura.
 */
@DisplayName("Controllers do core protegidos por @SaCheckPermission ou @SaIgnore aprovado")
class ControllersProtegidosTest {

    private static final String PACOTE_RAIZ = "org.alocacreche.core";
    private static final Pattern FORMATO_PERMISSAO = Pattern.compile("[a-z]+:[a-z]+:[a-z]+");
    private static final Set<String> ENDPOINTS_PUBLICOS_APROVADOS = Set.of("AuthController#login");

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
    @DisplayName("nenhum metodo dos controllers reais mistura @SaIgnore com @SaCheckPermission")
    void nenhumMetodoMisturaSaIgnoreComPermissao() {
        assertThat(metodosComSaIgnoreEPermissao(descobrirControllers())).isEmpty();
    }

    @Test
    @DisplayName("so os endpoints publicos aprovados usam @SaIgnore")
    void soEndpointsPublicosAprovadosUsamSaIgnore() {
        assertThat(metodosAbertos(descobrirControllers()))
                .containsExactlyInAnyOrderElementsOf(ENDPOINTS_PUBLICOS_APROVADOS);
    }

    @Test
    @DisplayName("a verificacao detecta metodo de controller sem nenhuma das duas anotacoes")
    void verificacaoDetectaMetodoSemProtecao() {
        assertThat(metodosSemProtecao(List.of(ControllerFicticioSemProtecao.class)))
                .containsExactly("ControllerFicticioSemProtecao#semAnotacao");
    }

    @Test
    @DisplayName("a verificacao detecta metodo com @SaIgnore e @SaCheckPermission ao mesmo tempo")
    void verificacaoDetectaSaIgnoreJuntoComPermissao() {
        assertThat(metodosComSaIgnoreEPermissao(List.of(ControllerFicticioComAmbas.class)))
                .containsExactly("ControllerFicticioComAmbas#ignoradoEProtegido");
    }

    @Test
    @DisplayName("a varredura encontra os controllers dos tres modulos e ignora controllers de teste")
    void varreduraEncontraOsControllersReais() {
        List<Class<?>> controllers = descobrirControllers();

        assertThat(controllers.stream().map(Class::getSimpleName).toList())
                .contains("AuthController", "CrecheTurmaController", "MatriculaController", "InscricaoController");
        assertThat(controllers).noneMatch(ControllersProtegidosTest::estaEmClassesDeTeste);
    }

    private static List<Class<?>> descobrirControllers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        return scanner.findCandidateComponents(PACOTE_RAIZ).stream()
                .map(BeanDefinition::getBeanClassName)
                .map(nome -> ClassUtils.resolveClassName(nome, ControllersProtegidosTest.class.getClassLoader()))
                .<Class<?>>map(classe -> classe)
                .filter(classe -> !estaEmClassesDeTeste(classe))
                .toList();
    }

    private static boolean estaEmClassesDeTeste(Class<?> classe) {
        String origem = classe.getProtectionDomain().getCodeSource().getLocation().getPath();
        return origem.contains("test-classes");
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
        @SaCheckPermission("matricula:ficticio:list")
        @GetMapping("/ficticio-ambas")
        public String ignoradoEProtegido() {
            return "ok";
        }
    }
}
