package io.github.mikel0ne.autoops.rag.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fuente de documentación desde Confluence. <b>Stub</b>: la abstracción está montada y registrada (la ingesta
 * ya la recorre), pero todavía no hay instancia de Confluence contra la que tirar, así que devuelve una lista
 * vacía. Cuando se monte el mini-Confluence, solo hay que rellenar {@link #leer()}: llamar a la REST API
 * ({@code /rest/api/content?spaceKey=...&expand=body.storage,version,ancestors}), mapear cada página a un
 * {@link Documento} (docId = pageId, urlBase = {@code <base>/pages/<id>}, breadcrumb desde {@code ancestors})
 * y convertir el {@code storage} HTML a markdown/texto antes de trocear.
 */
@Component
public class ConfluenceSource implements FuenteDocumental {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceSource.class);

    @Override
    public String nombre() {
        return "confluence";
    }

    @Override
    public List<Documento> leer() {
        log.debug("Fuente Confluence registrada pero sin implementar (stub): no aporta documentos todavía.");
        return List.of();
    }
}
