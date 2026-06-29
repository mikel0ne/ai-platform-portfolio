/**
 * Contratos del chat de búsqueda de AutoOps (front secundario).
 * Espejo de los DTOs del backend (docs/arquitectura/05-contratos-api-datos.md §2-3):
 * el endpoint `POST /search` reutiliza el núcleo RAG y redacta la respuesta con el LLM interno.
 */

/** Clase de origen del fragmento citado (DocChunk#origen en el backend). */
export type Origen = 'markdown-local' | 'git' | 'confluence';

/** Cita que respalda la respuesta: fichero del corpus, sección, origen/link y score de recuperación. */
export interface DocCitation {
  fuente: string;
  titulo: string;
  seccion: string;
  /** Enlace canónico con ancla a la sección; cadena vacía si el origen no es navegable (markdown-local). */
  url: string;
  origen: Origen;
  score: number;
}

/** Nivel de confianza del RAG: `baja` = el LLM evaluador no considera los chunks suficientes, el usuario debe matizar. */
export type Confianza = 'alta' | 'baja';

/** Petición a `POST /search`. */
export interface SearchRequest {
  consulta: string;
}

/** Respuesta de `POST /search`. */
export interface SearchResponse {
  respuesta: string;
  citas: DocCitation[];
  confianza: Confianza;
}

/** Mensaje renderizado en el hilo de búsqueda (modelo de vista). */
export interface SearchMessage {
  from: 'user' | 'agent';
  text: string;
  at: Date;
  citas?: DocCitation[];
  confianza?: Confianza;
  /** true si la burbuja es un error de red/servidor (estilo distinto, sin citas). */
  error?: boolean;
}
