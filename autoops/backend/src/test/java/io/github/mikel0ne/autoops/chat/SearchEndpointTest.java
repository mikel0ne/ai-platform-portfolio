package io.github.mikel0ne.autoops.chat;

import io.github.mikel0ne.autoops.error.ApiExceptionHandler;
import io.github.mikel0ne.autoops.rag.RagResult;
import io.github.mikel0ne.autoops.rag.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SearchEndpointTest {

    private final RagService ragService = mock(RagService.class);
    private final AnswerComposer answerComposer = mock(AnswerComposer.class);
    private final SearchRateLimiter rateLimiter = mock(SearchRateLimiter.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(rateLimiter.tryAcquire()).thenReturn(true);
        mockMvc = MockMvcBuilders.standaloneSetup(new SearchController(ragService, answerComposer, rateLimiter))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void answers_a_valid_query() throws Exception {
        when(ragService.search(anyString(), anyInt())).thenReturn(RagResult.empty(0));
        when(answerComposer.compose(anyString(), any())).thenReturn("respuesta");

        mockMvc.perform(post("/search").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"probes\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("respuesta"))
                .andExpect(jsonPath("$.confidence").value("none"));
    }

    @Test
    void rejects_a_blank_query_with_the_field_in_the_detail() throws Exception {
        mockMvc.perform(post("/search").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(startsWith("query:")));
    }

    @Test
    void malformed_json_is_a_client_error() throws Exception {
        mockMvc.perform(post("/search").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wrong_method_is_405() throws Exception {
        mockMvc.perform(get("/search"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void over_the_rate_limit_is_429_without_calling_the_llm() throws Exception {
        when(rateLimiter.tryAcquire()).thenReturn(false);

        mockMvc.perform(post("/search").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"probes\"}"))
                .andExpect(status().isTooManyRequests());
        verifyNoInteractions(ragService, answerComposer);
    }

    @Test
    void unexpected_failure_is_a_generic_500() throws Exception {
        when(ragService.search(anyString(), anyInt())).thenThrow(new IllegalStateException("weaviate down"));

        mockMvc.perform(post("/search").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"probes\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Error processing the request"));
    }
}
