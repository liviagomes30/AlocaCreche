package org.alocacreche.core.comum.seguranca.config;

import cn.dev33.satoken.fun.SaParamFunction;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SegurancaConfig implements WebMvcConfigurer {

    @Bean
    public StpLogicJwtForStateless stpLogicJwtForStateless() {
        return new StpLogicJwtForStateless();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptorSemRedespachoDeErro(handler -> StpUtil.checkLogin()))
                .addPathPatterns("/**");
    }

    static final class SaInterceptorSemRedespachoDeErro extends SaInterceptor {

        SaInterceptorSemRedespachoDeErro(SaParamFunction<Object> auth) {
            super(auth);
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws Exception {
            if (request.getDispatcherType() == DispatcherType.ERROR) {
                return true;
            }
            return super.preHandle(request, response, handler);
        }
    }
}
