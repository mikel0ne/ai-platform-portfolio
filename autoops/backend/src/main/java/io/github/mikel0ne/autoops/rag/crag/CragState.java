package io.github.mikel0ne.autoops.rag.crag;

import io.github.mikel0ne.autoops.rag.DocChunk;
import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;

public class CragState extends AgentState {

    public CragState(Map<String, Object> initData) {
        super(initData);
    }

    public String query() {
        return this.<String>value("query").orElseThrow();
    }

    public String currentQuery() {
        return this.<String>value("currentQuery").orElseGet(this::query);
    }

    public int k() {
        return this.<Integer>value("k").orElse(5);
    }

    public List<DocChunk> candidates() {
        return this.<List<DocChunk>>value("candidates").orElseGet(List::of);
    }

    public List<DocChunk> rawCandidates() {
        return this.<List<DocChunk>>value("rawCandidates").orElseGet(List::of);
    }

    public List<String> questions() {
        return this.<List<String>>value("questions").orElseGet(() -> List.of(query()));
    }

    public List<String> questionCoverage() {
        return this.<List<String>>value("questionCoverage").orElseGet(List::of);
    }

    public double bestScore() {
        return this.<Double>value("bestScore").orElse(0.0);
    }

    public boolean sufficient() {
        return this.<Boolean>value("sufficient").orElse(false);
    }

    public int iterations() {
        return this.<Integer>value("iterations").orElse(0);
    }
}
