package io.github.mikel0ne.autoops.wikijs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

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

    public List<PageSummary> listPages() {
        ListResponse resp = client.post()
                .uri("/graphql")
                .body(Map.of("query", QUERY_LIST))
                .retrieve()
                .body(ListResponse.class);
        if (resp == null) {
            return List.of();
        }
        if (hasErrors(resp.errors())) {
            return List.of();
        }
        if (resp.data() == null || resp.data().pages() == null || resp.data().pages().list() == null) {
            return List.of();
        }
        return resp.data().pages().list();
    }

    public PageDetail fetchPage(int id) {
        SingleResponse resp = client.post()
                .uri("/graphql")
                .body(Map.of("query", QUERY_SINGLE, "variables", Map.of("id", id)))
                .retrieve()
                .body(SingleResponse.class);
        if (resp == null || hasErrors(resp.errors())) {
            return null;
        }
        return resp.data() == null || resp.data().pages() == null ? null : resp.data().pages().single();
    }

    private boolean hasErrors(List<GraphQlError> errors) {
        if (errors == null || errors.isEmpty()) {
            return false;
        }
        log.warn("Wiki.js returned GraphQL errors: {}", errors);
        return true;
    }

    public record PageSummary(int id, String path, String locale, String title, String contentType,
                                 boolean isPublished, boolean isPrivate) {}

    public record PageDetail(int id, String path, String title, String description, String content,
                                 String contentType, String locale) {}

    record GraphQlError(String message) {}

    record ListResponse(ListData data, List<GraphQlError> errors) {}

    record ListData(PageQueryList pages) {}

    record PageQueryList(List<PageSummary> list) {}

    record SingleResponse(SingleData data, List<GraphQlError> errors) {}

    record SingleData(PageQuerySingle pages) {}

    record PageQuerySingle(PageDetail single) {}
}
