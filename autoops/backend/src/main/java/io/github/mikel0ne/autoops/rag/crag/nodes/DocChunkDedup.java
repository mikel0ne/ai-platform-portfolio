package io.github.mikel0ne.autoops.rag.crag.nodes;

import io.github.mikel0ne.autoops.rag.DocChunk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fusión y dedupe de listas de {@link DocChunk}. {@code DocChunk} no tiene id propio (es el contrato externo
 * del RAG, doc 05 §3), así que la clave de dedupe es fuente+sección+texto: si dos fragmentos comparten esos
 * tres campos, se consideran el mismo fragmento. En colisión gana el más reciente.
 */
final class DocChunkDedup {

    private DocChunkDedup() {
    }

    static List<DocChunk> merge(List<DocChunk> previos, List<DocChunk> nuevos) {
        Map<String, DocChunk> porClave = new LinkedHashMap<>();
        previos.forEach(c -> porClave.put(clave(c), c));
        nuevos.forEach(c -> porClave.put(clave(c), c));
        return List.copyOf(porClave.values());
    }

    private static String clave(DocChunk c) {
        return c.fuente() + "" + c.seccion() + "" + c.texto();
    }
}
