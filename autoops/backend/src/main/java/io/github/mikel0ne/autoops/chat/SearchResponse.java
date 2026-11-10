package io.github.mikel0ne.autoops.chat;

import io.github.mikel0ne.autoops.rag.Confidence;
import io.github.mikel0ne.autoops.rag.DocChunk;

import java.util.List;

public record SearchResponse(String answer, List<DocChunk> citations, Confidence confidence) {}
