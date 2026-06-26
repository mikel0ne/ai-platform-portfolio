/**
 * Contratos del chat de búsqueda de AutoOps (front secundario).
 * Espejo de los DTOs del backend (docs/arquitectura/05-contratos-api-datos.md §2-3):
 * el endpoint `POST /search` reutiliza el núcleo RAG y redacta la respuesta con el LLM interno.
 */

/** Cita que respalda la respuesta: fichero del corpus, sección y score de recuperación. */
export interface DocCitation {
  fuente: string;
  seccion: string;
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
