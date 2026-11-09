package io.github.mikel0ne.autoops.rag.source;

import io.github.mikel0ne.autoops.wikijs.WikiJsGateway;
import io.github.mikel0ne.autoops.wikijs.WikiJsGateway.PageDetail;
import io.github.mikel0ne.autoops.wikijs.WikiJsGateway.PageSummary;
import io.github.mikel0ne.autoops.wikijs.WikiJsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class WikiJsSource implements DocumentSource {

    private static final Logger log = LoggerFactory.getLogger(WikiJsSource.class);

    private final WikiJsGateway gateway;
    private final WikiJsProperties props;

    public WikiJsSource(WikiJsGateway gateway, WikiJsProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    @Override
    public String name() {
        return "wikijs";
    }

    @Override
    public List<SourceDocument> read() {
        if (props.url() == null || props.url().isBlank()) {
            log.debug("Wiki.js source disabled: autoops.wikijs.url is not set");
            return List.of();
        }
        List<SourceDocument> docs = new ArrayList<>();
        for (PageSummary summary : gateway.listPages()) {
            if (summary.isPrivate() || !summary.isPublished() || !"markdown".equals(summary.contentType())) {
                continue;
            }
            PageDetail detail = gateway.fetchPage(summary.id());
            if (detail == null) {
                continue;
            }
            docs.add(new SourceDocument(
                    "wikijs:" + detail.path(),
                    detail.title(),
                    detail.path(),
                    "wikijs",
                    pageUrl(detail),
                    detail.content()));
        }
        log.info("Wiki.js: {} documents from {}", docs.size(), props.url());
        return docs;
    }

    private String pageUrl(PageDetail detail) {
        String base = (props.publicUrl() == null || props.publicUrl().isBlank()) ? props.url() : props.publicUrl();
        return base + "/" + detail.locale() + "/" + detail.path();
    }
}
