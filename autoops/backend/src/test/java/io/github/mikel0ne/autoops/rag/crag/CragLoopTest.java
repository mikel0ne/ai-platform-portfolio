package io.github.mikel0ne.autoops.rag.crag;

import io.github.mikel0ne.autoops.config.PromptLoader;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import io.github.mikel0ne.autoops.rag.RagResult;
import io.github.mikel0ne.autoops.rag.RagService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El bucle correctivo (CRAG) sobre dobles: converge cuando el LLM evaluador da el visto bueno, reformula y
 * amplía {@code k} cuando no, y siempre termina (doc 03 §3, §5). Sin LLM real, sin Weaviate.
 */
class CragLoopTest {

    private final PromptLoader prompts = new PromptLoader();

    private RagService service(DocRetriever retriever, CragProperties props,
                                List<String> gradeRespuestas, List<String> rewriteRespuestas) {
        var chat = new TestSupport.ScriptedChatModel(gradeRespuestas, rewriteRespuestas);
        var graph = new CragGraph(retriever, TestSupport.provider(chat), props, prompts);
        return new RagService(graph);
    }

    @Test
    void converge_a_la_primera_sin_reformular() {
        var retriever = new TestSupport.FakeRetriever(0.9);
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1); // multiQueryN=1: abanico desactivado, no es lo que prueba este test

        RagResult result = service(retriever, props, List.of("1: SUFICIENTE"), List.of())
                .buscar("probes", 5);

        assertThat(result.iteraciones()).isZero();
        assertThat(result.confianza()).isEqualTo(RagResult.ALTA);
        assertThat(result.chunks()).isNotEmpty();
        assertThat(retriever.calls).isEqualTo(1);
        assertThat(retriever.kRecibidos).containsExactly(5);
    }

    @Test
    void reformula_una_vez_y_mejora() {
        var retriever = new TestSupport.FakeRetriever(0.2, 0.9);
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1); // multiQueryN=1: abanico desactivado, no es lo que prueba este test

        RagResult result = service(retriever, props,
                List.of("1: INSUFICIENTE", "1: SUFICIENTE\n2: INSUFICIENTE"), List.of("prbs reformulada"))
                .buscar("prbs", 5);

        assertThat(result.iteraciones()).isEqualTo(1);
        assertThat(result.confianza()).isEqualTo(RagResult.ALTA);
        assertThat(retriever.calls).isEqualTo(2); // retrieve inicial + retrieve tras rewrite
        assertThat(retriever.kRecibidos).containsExactly(5, 8); // k crece kStep=3 en el reintento
    }

    @Test
    void siempre_termina_y_no_cataloga_nada_como_bueno_si_todo_es_insuficiente() {
        var retriever = new TestSupport.FakeRetriever(0.2);
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1); // multiQueryN=1: abanico desactivado, no es lo que prueba este test

        RagResult result = service(retriever, props,
                List.of("1: INSUFICIENTE", "1: INSUFICIENTE\n2: INSUFICIENTE",
                        "1: INSUFICIENTE\n2: INSUFICIENTE\n3: INSUFICIENTE"),
                List.of("reformulada 1", "reformulada 2"))
                .buscar("consulta rara", 5);

        assertThat(result.iteraciones()).isEqualTo(2); // tope de reformulaciones
        assertThat(result.confianza()).isEqualTo(RagResult.NULA);
        assertThat(result.chunks()).isEmpty(); // nada catalogado como útil: no llega al redactor como cita
        assertThat(result.candidatosDescartados()).isNotEmpty(); // pero sí queda un último recurso disponible
        assertThat(retriever.calls).isEqualTo(3); // inicial + 2 reintentos
        assertThat(retriever.kRecibidos).containsExactly(5, 8, 11); // k sigue creciendo hasta el tope
    }

    @Test
    void cobertura_parcial_de_un_abanico_de_preguntas_da_confianza_media() {
        // multiQuery genera 2 preguntas (original + 1 variante, genuinamente distinta); el LLM declara
        // explícitamente cobertura solo para la original: ni alta (no todas cubiertas) ni baja
        // (una sí quedó CUBIERTA), sino media.
        var n1 = new DocChunk("Responde a la original.", "Guía", "probes.md", "A", "", "test", 0.9, "", 0, 0);
        var n2 = new DocChunk("No responde a nada en concreto.", "Guía", "probes.md", "B", "", "test",
                0.5, "", 0, 0);
        var retriever = new TestSupport.MultiChunkRetriever(List.of(List.of(n1, n2)));
        var props = new CragProperties(5, 0, 0.5, 3, 12, 2); // maxIteraciones=0: sin reintentos, resuelve a la 1ª
        var chat = new TestSupport.ScriptedChatModel(
                List.of("1: SUFICIENTE\n2: INSUFICIENTE\nPREGUNTA 1: CUBIERTA\nPREGUNTA 2: SIN_CUBRIR"),
                List.of(), List.of("variante"));
        var graph = new CragGraph(retriever, TestSupport.provider(chat), props, prompts);

        RagResult result = new RagService(graph).buscar("pregunta compuesta", 0);

        assertThat(result.confianza()).isEqualTo(RagResult.MEDIA);
        assertThat(result.chunks()).containsExactly(n1);
    }

    @Test
    void solo_ambiguos_en_todas_las_preguntas_da_confianza_baja() {
        var n1 = new DocChunk("Toca el tema pero no lo resuelve.", "Guía", "probes.md", "A", "", "test",
                0.9, "", 0, 0);
        var retriever = new TestSupport.MultiChunkRetriever(List.of(List.of(n1)));
        var props = new CragProperties(5, 0, 0.5, 3, 12, 1); // maxIteraciones=0, multiQueryN=1: 1 sola pregunta

        RagResult result = service(retriever, props, List.of("1: AMBIGUO\nPREGUNTA 1: PARCIAL"), List.of())
                .buscar("pregunta dudosa", 5);

        assertThat(result.confianza()).isEqualTo(RagResult.BAJA);
        assertThat(result.chunks()).containsExactly(n1); // el ambiguo se conserva como mejor esfuerzo
    }

    @Test
    void score_alto_pero_irrelevante_no_evita_la_evaluacion_del_llm() {
        // Regresión del bug visto en producción: un score crudo alto (reranker/híbrido) no implica que el
        // fragmento responda a la pregunta. 'grade' no debe aceptar por el score solo: si el LLM dice
        // INSUFICIENTE, el grafo reformula y reintenta aunque el score sea muy alto en ambas vueltas.
        var retriever = new TestSupport.FakeRetriever(5.0, 5.0);
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1); // multiQueryN=1: abanico desactivado, no es lo que prueba este test

        RagResult result = service(retriever, props,
                List.of("1: INSUFICIENTE", "1: INSUFICIENTE\n2: SUFICIENTE"), List.of("reformulada"))
                .buscar("recursos minimos de un microservicio", 5);

        assertThat(result.iteraciones()).isEqualTo(1);
        assertThat(retriever.calls).isEqualTo(2); // si hubiera atajo por score, sería 1
        assertThat(result.confianza()).isEqualTo(RagResult.ALTA);
    }

    @Test
    void abanico_multiquery_solo_en_la_primera_vuelta_se_fusiona_con_rrf() {
        var retriever = new TestSupport.FakeRetriever(0.9, 0.5, 0.5);
        var props = new CragProperties(2, 2, 0.5, 3, 12, 3); // k=2, multiQueryN=3
        var chat = new TestSupport.ScriptedChatModel(
                List.of("1: SUFICIENTE\n2: SUFICIENTE"), List.of(), List.of("variante uno\nvariante dos"));
        var graph = new CragGraph(retriever, TestSupport.provider(chat), props, prompts);

        RagResult result = new RagService(graph).buscar("consulta original", 0);

        // abanico: original + 2 variantes generadas, una sola vez (no se reabre en reintentos)
        assertThat(retriever.calls).isEqualTo(3);
        assertThat(retriever.consultasRecibidas)
                .containsExactly("consulta original", "variante uno", "variante dos");
        assertThat(retriever.kRecibidos).containsExactly(2, 2, 2); // cada variante pide k, no k/N
        // RRF empata las 3 (cada chunk aparece en una sola lista, rango 1): el orden de inserción decide
        // el desempate y el resultado se recorta a k=2.
        assertThat(result.chunks()).hasSize(2);
        assertThat(result.iteraciones()).isZero();
    }

    @Test
    void multiquery_n_uno_se_comporta_como_un_solo_retrieve_sin_llamar_al_llm_de_variantes() {
        var retriever = new TestSupport.FakeRetriever(0.9);
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1); // multiQueryN=1: equivalente al retrieve de toda la vida
        // sin respuestas de multiquery programadas: si el nodo llamara al LLM, fallaría con un veredicto vacío
        var chat = new TestSupport.ScriptedChatModel(List.of("1: SUFICIENTE"), List.of());
        var graph = new CragGraph(retriever, TestSupport.provider(chat), props, prompts);

        RagResult result = new RagService(graph).buscar("probes", 0);

        assertThat(retriever.calls).isEqualTo(1);
        assertThat(retriever.consultasRecibidas).containsExactly("probes");
        assertThat(result.confianza()).isEqualTo(RagResult.ALTA);
    }

    @Test
    void filtra_los_chunks_no_relevantes_del_lote() {
        // grade evalúa fragmento por fragmento en una sola llamada: de un lote con relevancia mixta, solo
        // los marcados relevantes deben sobrevivir (los demás nunca deben llegar al redactor).
        var bueno1 = new DocChunk("Las probes de liveness se configuran con...", "Guía", "probes.md",
                "Liveness", "", "test", 0.9, "", 0, 0);
        var malo = new DocChunk("La política de vacaciones del equipo es...", "RRHH", "rrhh.md",
                "Vacaciones", "", "test", 0.8, "", 0, 0);
        var bueno2 = new DocChunk("El timeout recomendado para probes es 5s", "Guía", "probes.md",
                "Timeouts", "", "test", 0.7, "", 0, 0);
        var retriever = new TestSupport.MultiChunkRetriever(List.of(List.of(bueno1, malo, bueno2)));
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1);

        RagResult result = service(retriever, props,
                List.of("1: SUFICIENTE\n2: INSUFICIENTE\n3: SUFICIENTE"), List.of())
                .buscar("configuracion de probes", 5);

        assertThat(result.chunks()).containsExactly(bueno1, bueno2);
        assertThat(result.confianza()).isEqualTo(RagResult.ALTA);
        assertThat(result.iteraciones()).isZero();
    }

    @Test
    void el_retry_no_pierde_un_chunk_bueno_de_la_primera_iteracion() {
        // Regresión: antes, retrieve sobreescribía 'candidatos' en cada vuelta, así que un chunk bueno de la
        // 1ª iteración se perdía en la 2ª si la consulta reformulada no volvía a traerlo en el nuevo top-k.
        var bueno = new DocChunk("Las probes de liveness se configuran con...", "Guía", "probes.md",
                "Liveness", "", "test", 0.9, "", 0, 0);
        var malo1 = new DocChunk("La política de vacaciones del equipo es...", "RRHH", "rrhh.md",
                "Vacaciones", "", "test", 0.1, "", 0, 0);
        var nuevo1 = new DocChunk("Otro fragmento sin relación con la consulta", "Otros", "otros.md",
                "Seccion X", "", "test", 0.5, "", 0, 0);
        var nuevo2 = new DocChunk("Otro fragmento más sin relación", "Otros", "otros.md",
                "Seccion Y", "", "test", 0.4, "", 0, 0);
        var retriever = new TestSupport.MultiChunkRetriever(List.of(
                List.of(bueno, malo1),
                List.of(nuevo1, nuevo2)));
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1);

        RagResult result = service(retriever, props,
                List.of(
                        "1: INSUFICIENTE\n2: INSUFICIENTE",                          // iter 1: nada relevante, fuerza retry
                        "1: SUFICIENTE\n2: INSUFICIENTE\n3: INSUFICIENTE\n4: INSUFICIENTE"), // iter 2: 'bueno' sigue ahí
                List.of("probes reformulada"))
                .buscar("configuracion de probes", 5);

        assertThat(result.iteraciones()).isEqualTo(1);
        assertThat(result.confianza()).isEqualTo(RagResult.ALTA);
        assertThat(result.chunks()).containsExactly(bueno);
    }

    @Test
    void caso_b_fusiona_a_la_seccion_un_hit_unico_en_seccion_pequena() {
        // Un fragmento relevante y corto cuya sección es pequeña (<= UMBRAL_PEQUENA) se expande a la sección
        // completa (auto-merging): el redactor recibe los núcleos de la sección unidos en una sola cita.
        var corto = new DocChunk("Ver tabla de timeouts.", "Guía", "probes.md", "Timeouts", "", "test",
                0.9, "doc-probes", 200, 0);
        var parte1 = new DocChunk("Las probes de liveness y readiness necesitan...",
                "Guía", "probes.md", "Timeouts", "", "test", 0.0, "doc-probes", 200, 0);
        var parte2 = new DocChunk("...tabla de timeouts recomendados por tipo de probe.",
                "Guía", "probes.md", "Timeouts", "", "test", 0.0, "doc-probes", 200, 1);
        var retriever = new TestSupport.MultiChunkRetriever(
                List.of(List.of(corto)),
                Map.of("doc-probes", List.of(parte1, parte2)));
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1);

        RagResult result = service(retriever, props, List.of("1: SUFICIENTE"), List.of())
                .buscar("timeouts de las probes", 5);

        assertThat(result.chunks()).hasSize(1); // una única cita: la sección fusionada
        assertThat(result.chunks().get(0).texto())
                .contains("Las probes de liveness", "tabla de timeouts recomendados");
        assertThat(result.chunks().get(0).score()).isEqualTo(0.9); // hereda el mejor score del grupo
        assertThat(result.confianza()).isEqualTo(RagResult.ALTA);
    }

    @Test
    void caso_a_fusiona_a_la_seccion_cuando_varios_nucleos_de_ella_son_relevantes() {
        // Dos núcleos de la misma sección sobreviven a grade (co-recuperación). Aunque la sección no sea
        // pequeña (5000 > UMBRAL_PEQUENA, < TOPE_FUSION), la señal de ≥2 hits dispara la fusión, que trae
        // ADEMÁS el núcleo de la sección que no se había recuperado.
        var n1 = new DocChunk("Núcleo uno de la sección.", "Guía", "probes.md", "Timeouts", "", "test",
                0.9, "doc-x", 5000, 0);
        var n2 = new DocChunk("Núcleo dos de la sección.", "Guía", "probes.md", "Timeouts", "", "test",
                0.6, "doc-x", 5000, 1);
        var f1 = new DocChunk("Núcleo uno de la sección.", "Guía", "probes.md", "Timeouts", "", "test",
                0.0, "doc-x", 5000, 0);
        var f2 = new DocChunk("Núcleo dos de la sección.", "Guía", "probes.md", "Timeouts", "", "test",
                0.0, "doc-x", 5000, 1);
        var f3 = new DocChunk("Núcleo tres, no recuperado.", "Guía", "probes.md", "Timeouts", "", "test",
                0.0, "doc-x", 5000, 2);
        var retriever = new TestSupport.MultiChunkRetriever(
                List.of(List.of(n1, n2)),
                Map.of("doc-x", List.of(f1, f2, f3)));
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1);

        RagResult result = service(retriever, props, List.of("1: SUFICIENTE\n2: SUFICIENTE"), List.of())
                .buscar("timeouts", 5);

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).texto())
                .contains("Núcleo uno", "Núcleo dos", "Núcleo tres");
        assertThat(retriever.seccionCalls).isEqualTo(1);
    }

    @Test
    void caso_c_da_vecinos_acotados_a_la_seccion_sin_fusionar() {
        // Un solo hit en sección grande (> UMBRAL_PEQUENA): NO se fusiona, pero se le da un poco de contexto
        // con sus vecinos ±1 DE LA MISMA SECCIÓN (radio 1). No lee la sección entera (vecinosEnSeccion, no
        // seccionCompleta) y no trae los núcleos lejanos.
        var antes = new DocChunk("Núcleo anterior.", "Guía", "probes.md", "Timeouts", "", "test",
                0.0, "doc-z", 5000, 3);
        var hit = new DocChunk("Núcleo del medio.", "Guía", "probes.md", "Timeouts", "", "test",
                0.9, "doc-z", 5000, 4);
        var despues = new DocChunk("Núcleo posterior.", "Guía", "probes.md", "Timeouts", "", "test",
                0.0, "doc-z", 5000, 5);
        var lejano = new DocChunk("Núcleo lejano.", "Guía", "probes.md", "Timeouts", "", "test",
                0.0, "doc-z", 5000, 9);
        var retriever = new TestSupport.MultiChunkRetriever(
                List.of(List.of(hit)),
                Map.of("doc-z", List.of(antes, hit, despues, lejano)));
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1);

        RagResult result = service(retriever, props, List.of("1: SUFICIENTE"), List.of())
                .buscar("timeouts", 5);

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).texto())
                .contains("Núcleo anterior", "Núcleo del medio", "Núcleo posterior")
                .doesNotContain("Núcleo lejano"); // radio 1 → no trae el de orden 9
        assertThat(retriever.seccionCalls).isZero();   // no fusiona
        assertThat(retriever.vecinosCalls).isEqualTo(1); // solo el fetch acotado de vecinos
    }

    @Test
    void techo_no_fusiona_si_la_seccion_es_demasiado_grande_aunque_haya_varios_hits() {
        // Aun con ≥2 hits, una sección por encima de TOPE_FUSION no se fusiona (evita diluir el contexto):
        // se conservan los fragmentos tal cual y no se consulta la sección ni los vecinos.
        var n1 = new DocChunk("Uno.", "Guía", "probes.md", "Timeouts", "", "test", 0.9, "doc-w", 12000, 0);
        var n2 = new DocChunk("Dos.", "Guía", "probes.md", "Timeouts", "", "test", 0.6, "doc-w", 12000, 1);
        var retriever = new TestSupport.MultiChunkRetriever(List.of(List.of(n1, n2)), Map.of());
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1);

        RagResult result = service(retriever, props, List.of("1: SUFICIENTE\n2: SUFICIENTE"), List.of())
                .buscar("timeouts", 5);

        assertThat(result.chunks()).containsExactly(n1, n2);
        assertThat(retriever.seccionCalls).isZero();
        assertThat(retriever.vecinosCalls).isZero();
    }
}
