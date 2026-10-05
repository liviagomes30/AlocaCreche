package org.alocacreche.relatorios.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.interceptor.SaInterceptor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

/**
 * Impede regressao de seguranca no MSRelatorios (AUTH-18 e AUTH-19): todo metodo de todo
 * {@code @RestController} de {@code org.alocacreche.relatorios} precisa de
 * {@code @SaCheckPermission} no formato {@code modulo:funcionalidade:operacao} ou de
 * {@code @SaIgnore}, e o {@link SaInterceptor} nunca pode declarar caminho de exclusao.
 */
@DisplayName("Controllers do MSRelatorios protegidos e sem caminho de exclusao no Sa-Token")
class ControllersProtegidosTest {

    private static final String PACOTE_RAIZ = "org.alocacreche.relatorios";
    private static final Pattern FORMATO_PERMISSAO = Pattern.compile("[a-z]+:[a-z]+:[a-z]+");

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
    @DisplayName("o SaInterceptor e registrado sem nenhum caminho de exclusao")
    void interceptorNaoDeclaraCaminhoDeExclusao() {
        InterceptorRegistry registry = new InterceptorRegistry();

        new SegurancaConfig().addInterceptors(registry);

        List<?> registros = (List<?>) ReflectionTestUtils.getField(registry, "registrations");
        assertThat(registros).hasSize(1);
        Object interceptor = ReflectionTestUtils.getField(registros.get(0), "interceptor");
        assertThat(interceptor).isInstanceOf(SaInterceptor.class);
        Object excluidos = ReflectionTestUtils.getField(registros.get(0), "excludePatterns");
        assertThat(excluidos == null || ((List<?>) excluidos).isEmpty()).isTrue();
    }

    private static List<Class<?>> descobrirControllers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        return scanner.findCandidateComponents(PACOTE_RAIZ).stream()
                .map(BeanDefinition::getBeanClassName)
                .map(nome -> ClassUtils.resolveClassName(nome, ControllersProtegidosTest.class.getClassLoader()))
                .<Class<?>>map(classe -> classe)
                .toList();
    }

    private static List<String> metodosSemProtecao(List<Class<?>> controllers) {
        return controllers.stream()
                .flatMap(controller -> metodosDeEndpoint(controller).stream())
                .filter(metodo -> !AnnotatedElementUtils.hasAnnotation(metodo, SaCheckPermission.class)
                        && !AnnotatedElementUtils.hasAnnotation(metodo, SaIgnore.class))
                .map(metodo -> metodo.getDeclaringClass().getSimpleName() + "#" + metodo.getName())
                .toList();
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
}
