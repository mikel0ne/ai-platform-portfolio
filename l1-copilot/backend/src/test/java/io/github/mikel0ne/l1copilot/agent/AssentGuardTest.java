package io.github.mikel0ne.l1copilot.agent;

import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier.RouterDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AssentGuardTest {

    private static final RouterDecision FORCED_CANCEL = new RouterDecision("CANCEL", 201L, "YES", "NONE");

    @ParameterizedTest
    @ValueSource(strings = {
            "vale", "ok", "OK.", "sí", "si", "Sí!!", "siii", "vaaale", "okey 👍", "👍", "✅",
            "de acuerdo", "vale, de acuerdo", "sí, adelante", "correcto, procede", "venga, dale", "pues vale",
            "sí por favor", "perfecto, gracias", "yes", "Yes please", "sure", "go ahead", "alright, do it",
            "oui", "d'accord", "ouais, vas-y", "sim", "pode ser", "certo, obrigado", "d'acord", "endavant",
            "bai", "ados", "aurrera, mesedez", "s​í", "ｏｋ", "sí, sí, claro"})
    @DisplayName("A bare assent is recognised in any supported language, case, accent, repetition or width")
    void recognisesBareAssent(String message) {
        assertThat(AssentGuard.isBareAssent(message)).as(message).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "cancela el pedido 201, sí", "sí, cancélalo", "vale, devuélvelo", "ok, ¿dónde está?", "sí, ese",
            "sí, el portátil", "no", "vale no", "sí pero no", "201", "ok 201", "gracias", "por favor",
            "hola", "%%%$$$", "", "   ", "yes cancel it", "oui, annulez la commande", "sim, quero devolver",
            "bai, ezeztatu", "sí, adelante, y ábreme una incidencia porque llegó roto",
            "vale, gracias, ¿y el otro?", "😊", "ок"})
    @DisplayName("Anything that carries its own request, a number, a refusal or unknown words is not a bare assent")
    void rejectsMessagesWithContent(String message) {
        assertThat(AssentGuard.isBareAssent(message)).as(message).isFalse();
    }

    @Test
    @DisplayName("Null and over-long messages are not bare assents")
    void rejectsNullAndLongMessages() {
        assertThat(AssentGuard.isBareAssent(null)).isFalse();
        assertThat(AssentGuard.isBareAssent("vale, " + "sí, ".repeat(AssentGuard.MAX_LENGTH))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCEL", "RETURN", "SUPPORT"})
    @DisplayName("With nothing awaited, a bare assent cannot land on an action and loses any order id and confirmation")
    void bareAssentNeverTriggersAnAction(String action) {
        RouterDecision forced = new RouterDecision(action, 201L, "YES", "NONE", true);

        RouterDecision guarded = AssentGuard.apply(forced, Awaiting.NOTHING, Optional.empty(), "vale");

        assertThat(guarded.intent()).isEqualTo("OTHER");
        assertThat(guarded.orderId()).isNull();
        assertThat(guarded.confirmation()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("A bare assent to an action the system itself offered keeps that action, whatever the LLM said")
    void offeredActionIsAccepted() {
        RouterDecision llm = new RouterDecision("OTHER", null, "NONE", "NONE");

        RouterDecision guarded = AssentGuard.apply(llm, Awaiting.NOTHING, Optional.of(Intent.CANCEL), "sí");

        assertThat(guarded.intent()).isEqualTo("CANCEL");
        assertThat(guarded.orderId()).isNull();
    }

    @Test
    @DisplayName("A read intent on a bare assent is left alone (it executes nothing), but without an order id")
    void readIntentIsKeptWithoutOrderId() {
        RouterDecision llm = new RouterDecision("TRACKING", 300L, "NONE", "NONE");

        RouterDecision guarded = AssentGuard.apply(llm, Awaiting.NOTHING, Optional.empty(), "ok");

        assertThat(guarded.intent()).isEqualTo("TRACKING");
        assertThat(guarded.orderId()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = Awaiting.class, names = "NOTHING", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("While something is really awaited the decision is untouched: confirmations keep working")
    void realPendingStateIsUntouched(Awaiting awaiting) {
        assertThat(AssentGuard.apply(FORCED_CANCEL, awaiting, Optional.empty(), "sí")).isSameAs(FORCED_CANCEL);
    }

    @Test
    @DisplayName("A message with its own request is untouched even with nothing awaited")
    void concreteRequestIsUntouched() {
        assertThat(AssentGuard.apply(FORCED_CANCEL, Awaiting.NOTHING, Optional.empty(), "cancela el pedido 201, sí"))
                .isSameAs(FORCED_CANCEL);
    }
}
