package io.github.mikel0ne.autoops.rag.source;

import io.github.mikel0ne.autoops.wikijs.WikiJsGateway;
import io.github.mikel0ne.autoops.wikijs.WikiJsGateway.PaginaDetalle;
import io.github.mikel0ne.autoops.wikijs.WikiJsGateway.PaginaResumen;
import io.github.mikel0ne.autoops.wikijs.WikiJsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fuente de documentación desde Wiki.js (wiki self-hosted, ver infra/INFRA.md §7): trae las páginas
 * publicadas y no privadas vía la API GraphQL. Sustituye al stub que dejó {@code ConfluenceSource}. El
 * contenido de estas páginas se versiona como backup en {@code wiki/content/*.txt} (no {@code .md}, para
 * que {@link GitHubMarkdownSource} no lo re-ingeste) y se publica con {@code wiki/scripts/seed-wiki-content.sh}.
 */
@Component
public class WikiJsSource implements FuenteDocumental {

    private static final Logger log = LoggerFactory.getLogger(WikiJsSource.class);

    private final WikiJsGateway gateway;
    private final WikiJsProperties props;

    public WikiJsSource(WikiJsGateway gateway, WikiJsProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    @Override
    public String nombre() {
        return "wikijs";
    }

    @Override
    public List<Documento> leer() {
        if (props.url() == null || props.url().isBlank()) {
            log.debug("Fuente Wiki.js desactivada: autoops.wikijs.url no configurada");
            return List.of();
        }
        List<Documento> docs = new ArrayList<>();
        for (PaginaResumen resumen : gateway.listarPaginas()) {
            if (resumen.isPrivate() || !resumen.isPublished() || !"markdown".equals(resumen.contentType())) {
                continue;
            }
            PaginaDetalle detalle = gateway.obtenerPagina(resumen.id());
            if (detalle == null) {
                continue;
            }
            docs.add(new Documento(
                    "wikijs:" + detalle.path(),
                    detalle.title(),
                    detalle.path(),
                    "wikijs",
                    urlPagina(detalle),
                    detalle.content()));
        }
        log.info("Wiki.js: {} documentos desde {}", docs.size(), props.url());
        return docs;
    }

    private String urlPagina(PaginaDetalle detalle) {
        String base = (props.publicUrl() == null || props.publicUrl().isBlank()) ? props.url() : props.publicUrl();
        return base + "/" + detalle.locale() + "/" + detalle.path();
    }
}
