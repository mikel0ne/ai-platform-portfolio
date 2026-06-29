package io.github.mikel0ne.autoops.wikijs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Cliente de la API GraphQL de Wiki.js (solo lectura: la escritura/siembra del contenido versionado en
 * {@code wiki/content/} va por {@code wiki/scripts/seed-wiki-content.sh}, no por este Gateway). Wiki.js
 * devuelve HTTP 200 con un array {@code errors} incluso en fallos de autenticación; este Gateway lo
 * inspecciona y degrada a una respuesta vacía en vez de propagar la excepción, para no romper la ingesta
 * global si la fuente está mal configurada (mismo criterio que las demás fuentes opcionales).
 */
@Service
public class WikiJsGateway {

    private static final Logger log = LoggerFactory.getLogger(WikiJsGateway.class);

    private static final String QUERY_LIST = """
            query {
              pages {
                list {
                  id
                  path
                  locale
                  title
                  contentType
                  isPublished
                  isPrivate
                }
              }
            }
            """;

    private static final String QUERY_SINGLE = """
            query ($id: Int!) {
              pages {
                single(id: $id) {
                  id
                  path
                  title
                  description
                  content
                  contentType
                  locale
                }
              }
            }
            """;

    private final RestClient client;

    public WikiJsGateway(RestClient wikiJsClient) {
        this.client = wikiJsClient;
    }

    /** Todas las páginas (resumen, sin contenido) de la wiki, en el locale por defecto del servidor. */
    public List<PaginaResumen> listarPaginas() {
        ListResponse resp = client.post()
                .uri("/graphql")
                .body(Map.of("query", QUERY_LIST))
                .retrieve()
                .body(ListResponse.class);
        if (resp == null) {
            return List.of();
        }
        if (tieneErrores(resp.errors())) {
            return List.of();
        }
        if (resp.data() == null || resp.data().pages() == null || resp.data().pages().list() == null) {
            return List.of();
        }
        return resp.data().pages().list();
    }

    /** Contenido completo de una página por id; {@code null} si no existe o la llamada falla. */
    public PaginaDetalle obtenerPagina(int id) {
        SingleResponse resp = client.post()
                .uri("/graphql")
                .body(Map.of("query", QUERY_SINGLE, "variables", Map.of("id", id)))
                .retrieve()
                .body(SingleResponse.class);
        if (resp == null || tieneErrores(resp.errors())) {
            return null;
        }
        return resp.data() == null || resp.data().pages() == null ? null : resp.data().pages().single();
    }

    private boolean tieneErrores(List<GraphQlError> errores) {
        if (errores == null || errores.isEmpty()) {
            return false;
        }
        log.warn("Wiki.js devolvió errores GraphQL: {}", errores);
        return true;
    }

    public record PaginaResumen(int id, String path, String locale, String title, String contentType,
                                 boolean isPublished, boolean isPrivate) {}

    public record PaginaDetalle(int id, String path, String title, String description, String content,
                                 String contentType, String locale) {}

    record GraphQlError(String message) {}

    record ListResponse(ListData data, List<GraphQlError> errors) {}

    record ListData(PageQueryList pages) {}

    record PageQueryList(List<PaginaResumen> list) {}

    record SingleResponse(SingleData data, List<GraphQlError> errors) {}

    record SingleData(PageQuerySingle pages) {}

    record PageQuerySingle(PaginaDetalle single) {}
}
