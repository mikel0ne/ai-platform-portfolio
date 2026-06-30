package io.github.mikel0ne.autoops.rag;

/**
 * Fragmento listo para indexar. El texto que se embebe y el que se almacena/devuelve son el mismo (el
 * núcleo): no se hornea ninguna ventana de vecinos en ingesta. El contexto extra (sección completa) lo
 * resuelve el bucle CRAG en tiempo de consulta a partir de los metadatos de jerarquía (patrón
 * <i>small-to-big</i>: se recupera y se juzga la unidad pequeña, se expande solo para el redactor).
 *
 * @param texto       contenido del fragmento (el núcleo: lo que se embebe y lo que se almacena/cita)
 * @param docId       id estable del documento padre
 * @param titulo      título del documento de origen
 * @param fuente      nombre corto del origen (fichero/page/path)
 * @param seccion     breadcrumb de encabezados hasta este fragmento (p. ej. {@code Operación > Probes > Readiness})
 * @param url         enlace canónico con ancla a la sección (cadena vacía si el origen no es navegable)
 * @param origen      clase de origen: {@code markdown-local} | {@code git} | {@code wikijs}
 * @param orden       posición del fragmento dentro de su documento (desde 0)
 * @param hash        hash del texto; habilita ingesta incremental y dedupe
 * @param seccionChars tamaño total (caracteres) de la sección a la que pertenece; lo usa el CRAG para
 *                     decidir si expandir un fragmento a su sección completa sin consultar Weaviate
 */
public record Chunk(
        String texto,
        String docId,
        String titulo,
        String fuente,
        String seccion,
        String url,
        String origen,
        int orden,
        String hash,
        int seccionChars) {
}
