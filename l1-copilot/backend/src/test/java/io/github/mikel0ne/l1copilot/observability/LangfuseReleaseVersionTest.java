package io.github.mikel0ne.l1copilot.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.w3c.dom.Node;

import java.io.File;
import java.util.Map;
import java.util.Properties;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;

class LangfuseReleaseVersionTest {

    private static final String PREFIX = "copilot.langfuse";

    @Test
    void release_defaults_to_the_project_version_from_the_pom() throws Exception {
        LangfuseProperties bound = bind(Map.of());

        assertThat(bound.release()).isEqualTo(pomVersion()).doesNotContain("@");
    }

    @Test
    void release_can_be_forced_by_property() throws Exception {
        LangfuseProperties bound = bind(Map.of(PREFIX + ".release", "9.9.9"));

        assertThat(bound.release()).isEqualTo("9.9.9");
    }

    private static LangfuseProperties bind(Map<String, Object> overrides) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties file = yaml.getObject();
        Binder binder = new Binder(
                new MapConfigurationPropertySource(overrides),
                new MapConfigurationPropertySource(file));
        return binder.bind(PREFIX, LangfuseProperties.class).get();
    }

    private static String pomVersion() throws Exception {
        var root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new File("pom.xml")).getDocumentElement();
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if ("version".equals(n.getNodeName())) return n.getTextContent().trim();
        }
        throw new IllegalStateException("pom.xml has no project version");
    }
}
