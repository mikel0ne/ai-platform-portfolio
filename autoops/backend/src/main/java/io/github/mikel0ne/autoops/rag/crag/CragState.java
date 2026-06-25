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

    /** Mejor score del conjunto actual (lo fija {@code grade}). */
    public double mejorScore() {
        return this.<Double>value("mejorScore").orElse(0.0);
    }

    /** Reformulaciones consumidas (lo incrementa {@code rewrite}). */
    public int iteraciones() {
        return this.<Integer>value("iteraciones").orElse(0);
    }
}
