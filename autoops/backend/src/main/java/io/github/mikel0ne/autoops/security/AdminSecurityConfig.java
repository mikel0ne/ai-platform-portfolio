package io.github.mikel0ne.autoops.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdminSecurityConfig {

    public static final String MCP_HEADER = "X-MCP-Token";

    @Bean
    FilterRegistrationBean<AdminTokenFilter> adminTokenFilter(@Value("${autoops.admin.token:}") String token) {
        FilterRegistrationBean<AdminTokenFilter> registration = new FilterRegistrationBean<>(new AdminTokenFilter(token));
        registration.addUrlPatterns("/admin/*");
        return registration;
    }

    // Opt-in so the existing MCP clients keep working until they send the header; set the token to close /mcp.
    @Bean
    FilterRegistrationBean<AdminTokenFilter> mcpTokenFilter(@Value("${autoops.mcp.token:}") String token) {
        FilterRegistrationBean<AdminTokenFilter> registration =
                new FilterRegistrationBean<>(new AdminTokenFilter(MCP_HEADER, token, true));
        registration.addUrlPatterns("/mcp", "/mcp/*");
        return registration;
    }
}
