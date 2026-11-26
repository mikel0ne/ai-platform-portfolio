package io.github.mikel0ne.l1copilot.controller;

import io.github.mikel0ne.l1copilot.domain.repository.UserRepository;
import io.github.mikel0ne.l1copilot.security.TokenSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import(TokenSigner.class)
@TestPropertySource(properties = "copilot.auth.token-secret=controller-test-secret")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenSigner tokenSigner;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("A known demo user gets a token that verifies back to its id")
    void knownUserGetsVerifiableToken() throws Exception {
        when(userRepository.existsById("u-001")).thenReturn(true);

        String body = mockMvc.perform(post("/copilot/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u-001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String token = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        assertThat(tokenSigner.verify(token)).contains("u-001");
    }

    @Test
    @DisplayName("An unknown user: 401")
    void unknownUserIsUnauthorized() throws Exception {
        when(userRepository.existsById("u-999")).thenReturn(false);

        mockMvc.perform(post("/copilot/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u-999\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A blank user id: 400")
    void blankUserIdIsRejected() throws Exception {
        mockMvc.perform(post("/copilot/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\" \"}"))
                .andExpect(status().isBadRequest());
    }
}
