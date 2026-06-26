package io.github.mikel0ne.autoops.rag.source;

import java.util.List;

/**
 * Origen de documentación a ingestar en el RAG. Abstrae de dónde salen los documentos (carpeta local de
 * markdown, repositorio Git, Confluence...) para que la ingesta sea agnóstica al origen: el
 * {@link io.github.mikel0ne.autoops.rag.IngestionService} recorre todas las implementaciones disponibles y trocea lo
 * que cada una emite.
 *
 * <p>Cada implementación es un {@code @Component}; Spring las inyecta todas como {@code List<FuenteDocumental>}.
 * Una fuente no configurada debe devolver una lista vacía (no fallar), para poder convivir con las demás.
 */
public interface FuenteDocumental {

    /** Nombre corto del origen, solo para logs (p. ej. {@code markdown-local}, {@code git}). */
    String nombre();

    /** Lee todos los documentos del origen. Vacío si la fuente no está configurada. */
    List<Documento> leer();
}
