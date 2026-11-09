package io.github.mikel0ne.autoops.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdminSecurityConfig {

    @Bean
    FilterRegistrationBean<AdminTokenFilter> adminTokenFilter(@Value("${autoops.admin.token:}") String token) {
        FilterRegistrationBean<AdminTokenFilter> registration = new FilterRegistrationBean<>(new AdminTokenFilter(token));
        registration.addUrlPatterns("/admin/*");
        return registration;
    }
}
