package io.github.mikel0ne.l1copilot.agent.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionParserTest {

    @Test
    void parsesPlainJson() {
        var d = DecisionParser.parse("{\"verdict\":\"REJECT\",\"reason\":\"no es una consulta\"}");
        assertThat(d.verdict()).isEqualTo("REJECT");
        assertThat(d.reason()).isEqualTo("no es una consulta");
    }

    @Test
    void parsesFencedJsonWithRawNewlineInReason() {
        String raw = "```json\n{\n\"verdict\": \"SURE\",\n\"reason\": \"El usuario\n\n pregunta por su pedido\"\n}\n```";
        var d = DecisionParser.parse(raw);
        assertThat(d.verdict()).isEqualTo("SURE");
        assertThat(d.reason()).contains("El usuario").contains("pregunta por su pedido");
    }

    @Test
    void keepsEscapedQuotesAndEscapesIntact() {
        var d = DecisionParser.parse("{\"verdict\":\"SURE\",\"reason\":\"dice \\\"hola\\\"\\ny\tadios\"}");
        assertThat(d.verdict()).isEqualTo("SURE");
        assertThat(d.reason()).contains("\"hola\"").contains("adios");
    }

    @Test
    void degradesToDoubtOnGarbage() {
        assertThat(DecisionParser.parse("lo siento, no puedo ayudar").verdict()).isEqualTo("DOUBT");
        assertThat(DecisionParser.parse("{ esto no es json").verdict()).isEqualTo("DOUBT");
        assertThat(DecisionParser.parse("").verdict()).isEqualTo("DOUBT");
        assertThat(DecisionParser.parse(null).verdict()).isEqualTo("DOUBT");
    }

    @Test
    void degradesToDoubtOnUnknownVerdict() {
        assertThat(DecisionParser.parse("{\"verdict\":\"MAYBE\",\"reason\":\"x\"}").verdict()).isEqualTo("DOUBT");
        assertThat(DecisionParser.parse("{\"reason\":\"x\"}").verdict()).isEqualTo("DOUBT");
    }
}
