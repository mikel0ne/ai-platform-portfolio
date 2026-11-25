package io.github.mikel0ne.autoops.observability.langfuse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

// Binds the real application.yml without a Spring context: guards the YAML/record names and that the
// committed default stays disabled.
class LangfuseConfigBindingTest {

    @Test
    void application_yml_binds_and_ships_disabled() {
        LangfuseProperties props = bind();

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.endpoint()).isEqualTo("http://langfuse.local/api/public/otel");
        assertThat(props.environment()).isEqualTo("local");
        assertThat(props.isCaptureContent()).isTrue();
        assertThat(props.sampleRatio()).isEqualTo(1.0);
        assertThat(props.tracesUrl()).endsWith("/api/public/otel/v1/traces");
    }

    @Test
    void without_the_environment_variables_the_keys_are_empty() {
        assertThat(bind().hasCredentials()).isFalse();
    }

    private static LangfuseProperties bind() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        Map<String, Object> source = new HashMap<>();
        properties.forEach((k, v) -> source.put(String.valueOf(k), v));

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("application.yml", source));

        return Binder.get(environment).bind("autoops.langfuse", LangfuseProperties.class).get();
    }
}
