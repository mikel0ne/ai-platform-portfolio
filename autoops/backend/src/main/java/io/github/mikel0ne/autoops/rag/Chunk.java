package io.github.mikel0ne.autoops.rag;

/**
 * Fragmento listo para indexar. Distingue dos textos a propósito (patrón <i>sentence-window</i>):
 * <ul>
 *   <li>{@code nucleo}: el texto que se <b>embebe</b> (define qué se recupera). Pequeño y limpio → match preciso.</li>
 *   <li>{@code texto}: el texto que se <b>almacena y se devuelve</b> al LLM (núcleo + el chunk anterior y el
 *       posterior del mismo documento). Más ancho → el redactor tiene contexto suficiente para responder.</li>
 * </ul>
 * Como AutoOps calcula los embeddings a mano ({@code vectorizer: none} en Weaviate), separar ambos textos es
 * gratis: se embebe {@code nucleo} y se guarda {@code texto}.
 *
 * @param nucleo  texto que se embebe (núcleo del fragmento)
 * @param texto   texto que se almacena y se cita (ventana: chunk anterior + núcleo + chunk siguiente)
 * @param docId   id estable del documento padre
 * @param titulo  título del documento de origen
 * @param fuente  nombre corto del origen (fichero/page/path)
 * @param seccion breadcrumb de encabezados hasta este fragmento (p. ej. {@code Operación > Probes > Readiness})
 * @param url     enlace canónico con ancla a la sección (cadena vacía si el origen no es navegable)
 * @param origen  clase de origen: {@code markdown-local} | {@code git} | {@code wikijs}
 * @param orden   posición del fragmento dentro de su documento (desde 0)
 * @param hash    hash del {@code nucleo}; habilita ingesta incremental y dedupe
 */
public record Chunk(
        String nucleo,
        String texto,
        String docId,
        String titulo,
        String fuente,
        String seccion,
        String url,
        String origen,
        int orden,
        String hash) {
}
