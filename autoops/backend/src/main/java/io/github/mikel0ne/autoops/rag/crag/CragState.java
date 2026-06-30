package io.github.mikel0ne.autoops.rag.crag;

import io.github.mikel0ne.autoops.rag.DocChunk;
import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;

/**
 * Estado del subgrafo correctivo (CRAG). Es un POJO de LangGraph4j (no conoce Spring): se testea sin
 * contexto. Los nodos devuelven deltas (mapas) que LangGraph4j fusiona sobre este estado (doc 03 §3.1).
 */
public class CragState extends AgentState {

    public CragState(Map<String, Object> initData) {
        super(initData);
    }

    /** Consulta original del usuario (no cambia durante el bucle). */
    public String consulta() {
        return this.<String>value("consulta").orElseThrow();
    }

    /** Consulta vigente (reformulada por {@code rewrite}); de inicio, la original. */
    public String consultaActual() {
        return this.<String>value("consultaActual").orElseGet(this::consulta);
    }

    /** Nº de fragmentos a recuperar/devolver. */
    public int k() {
        return this.<Integer>value("k").orElse(5);
    }

    /** Candidatos recuperados/reranqueados en la iteración actual. */
    public List<DocChunk> candidatos() {
        return this.<List<DocChunk>>value("candidatos").orElseGet(List::of);
    }

    /** Pool sin filtrar de la última iteración, justo antes de {@code grade} (lo fija {@code rerank}). Solo
     * se usa como último recurso cuando nada se cataloga como bueno (confianza {@code nula}). */
    public List<DocChunk> candidatosCrudos() {
        return this.<List<DocChunk>>value("candidatosCrudos").orElseGet(List::of);
    }

    /** Lista de preguntas a cubrir: la posición 1 es siempre la original; el resto, si {@code multiQuery}
     * generó variantes, son las reformulaciones del abanico. De inicio (sin abanico), solo la original. */
    public List<String> preguntas() {
        return this.<List<String>>value("preguntas").orElseGet(() -> List.of(consulta()));
    }

    /** Cobertura por pregunta tras el último {@code grade}: {@code CUBIERTA}/{@code PARCIAL}/{@code SIN_CUBRIR},
     * en el mismo orden que {@link #preguntas()}. Vacía hasta el primer {@code grade}. */
    public List<String> coberturaPreguntas() {
        return this.<List<String>>value("coberturaPreguntas").orElseGet(List::of);
    }

    /** Mejor score del conjunto actual (lo fija {@code grade}). */
    public double mejorScore() {
        return this.<Double>value("mejorScore").orElse(0.0);
    }

    /** Veredicto de {@code grade}: ¿los candidatos actuales bastan para responder? De inicio, {@code false}. */
    public boolean suficiente() {
        return this.<Boolean>value("suficiente").orElse(false);
    }

    /** Reformulaciones consumidas (lo incrementa {@code rewrite}). */
    public int iteraciones() {
        return this.<Integer>value("iteraciones").orElse(0);
    }
}
