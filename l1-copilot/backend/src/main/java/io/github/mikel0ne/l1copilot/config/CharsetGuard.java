package io.github.mikel0ne.l1copilot.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

@Component
public class CharsetGuard {

    @PostConstruct
    void check() {
        requireUtf8(Charset.defaultCharset());
    }

    // Prompt files are read with the JVM default charset by LangChain4j (fromResource).
    static void requireUtf8(Charset charset) {
        if (!StandardCharsets.UTF_8.equals(charset)) {
            throw new IllegalStateException("The JVM default charset is " + charset
                    + ", start it with -Dfile.encoding=UTF-8");
        }
    }
}
