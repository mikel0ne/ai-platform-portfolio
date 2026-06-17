package io.github.mikel0ne.l1copilot.controller;

import io.github.mikel0ne.l1copilot.config.TreatmentPolicy;
import io.github.mikel0ne.l1copilot.domain.User;
import io.github.mikel0ne.l1copilot.domain.repository.UserRepository;
import io.github.mikel0ne.l1copilot.service.ConversationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConversationService conversationService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private TreatmentPolicy treatmentPolicy;

    @Test
    @DisplayName("Un turno válido devuelve la respuesta del agente")
    void validTurnReturnsAgentReply() throws Exception {
        when(userRepository.existsById("u-001")).thenReturn(true);
        when(conversationService.chat(eq("u-001"), eq("conv-1"), any()))
                .thenReturn(new ConversationService.ChatResult("Tu pedido está en reparto", "TRACKING", true, List.of(), List.of(), null));

        mockMvc.perform(post("/copilot/chat")
                        .header("X-User-Id", "u-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"conv-1","message":"¿Dónde está mi pedido?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value("conv-1"))
                .andExpect(jsonPath("$.reply").value("Tu pedido está en reparto"))
                .andExpect(jsonPath("$.intent").value("TRACKING"))
                .andExpect(jsonPath("$.resolved").value(true));
    }

    @Test
    @DisplayName("Sin cabecera de sesión: 401 problem+json")
    void missingSessionHeaderIsUnauthorized() throws Exception {
        mockMvc.perform(post("/copilot/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"conv-1","message":"hola"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Usuario desconocido: 401 sin revelar detalles")
    void unknownUserIsUnauthorized() throws Exception {
        when(userRepository.existsById("u-999")).thenReturn(false);

        mockMvc.perform(post("/copilot/chat")
                        .header("X-User-Id", "u-999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"conv-1","message":"hola"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Mensaje en blanco: 400 antes de gastar tokens")
    void blankMessageIsRejected() throws Exception {
        when(userRepository.existsById("u-001")).thenReturn(true);

        mockMvc.perform(post("/copilot/chat")
                        .header("X-User-Id", "u-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"conv-1","message":"   "}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("El asistente caído se traduce a 503 con mensaje amable")
    void llmUnavailableIs503() throws Exception {
        when(userRepository.existsById("u-001")).thenReturn(true);
        when(conversationService.chat(any(), any(), any()))
                .thenThrow(new ConversationService.LlmUnavailableException(new RuntimeException("quota")));

        mockMvc.perform(post("/copilot/chat")
                        .header("X-User-Id", "u-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"conv-1","message":"hola"}
                                """))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("Los usuarios de demo alimentan el login simulado del frontend")
    void demoUsersAreListed() throws Exception {
        User ana = new User();
        ana.setId("u-001");
        ana.setName("Ana García");
        ana.setEmail("ana@example.com");
        ana.setAge(28);
        when(userRepository.findAll()).thenReturn(List.of(ana));
        when(treatmentPolicy.isFormal(ana)).thenReturn(false);

        mockMvc.perform(get("/copilot/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("u-001"))
                .andExpect(jsonPath("$[0].name").value("Ana García"))
                .andExpect(jsonPath("$[0].age").value(28))
                .andExpect(jsonPath("$[0].formal").value(false));
    }
}
