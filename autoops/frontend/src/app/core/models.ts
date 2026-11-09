// Mirrors the DTOs of POST /search in the backend (SearchRequest, SearchResponse, DocChunk).

export type Origin = 'markdown-local' | 'git' | 'wikijs';

export interface DocCitation {
  source: string;
  title: string;
  section: string;
  // Canonical link anchored to the section; empty when the origin is not browsable (markdown-local).
  url: string;
  origin: Origin;
  score: number;
}

export type Confidence = 'high' | 'medium' | 'low' | 'none';

export interface SearchRequest {
  query: string;
}

export interface SearchResponse {
  answer: string;
  citations: DocCitation[];
  confidence: Confidence;
}

export interface SearchMessage {
  from: 'user' | 'agent';
  text: string;
  at: Date;
  citations?: DocCitation[];
  confidence?: Confidence;
  error?: boolean;
}
