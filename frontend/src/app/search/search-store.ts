import { Injectable, inject, signal } from '@angular/core';
import { finalize } from 'rxjs/operators';
import { SearchApiService } from '../core/search-api.service';
import { SearchMessage } from '../core/models';

/**
 * Estado del chat de búsqueda. Sin historial persistido ni sesión: cada consulta
 * es independiente (la doc define el chat como deliberadamente fino, sin auditoría).
 */
@Injectable()
export class SearchStore {
  private readonly api = inject(SearchApiService);

  readonly messages = signal<SearchMessage[]>([]);
  readonly searching = signal(false);

  /** Lanza una consulta contra el RAG y añade pregunta + respuesta al hilo. */
  search(consulta: string): void {
    const message = consulta.trim();
    if (this.searching() || !message) return;

    this.messages.update(m => [...m, { from: 'user', text: message, at: new Date() }]);
    this.searching.set(true);

    this.api.search({ consulta: message })
      .pipe(finalize(() => this.searching.set(false)))
      .subscribe({
        next: r => this.messages.update(m => [
          ...m,
          { from: 'agent', text: r.respuesta, citas: r.citas, confianza: r.confianza, at: new Date() },
        ]),
        error: () => this.messages.update(m => [
          ...m,
          {
            from: 'agent',
            text: 'No he podido consultar la documentación ahora mismo. Inténtalo de nuevo en unos instantes.',
            error: true,
            at: new Date(),
          },
        ]),
      });
  }

  /** Vacía el hilo para empezar una búsqueda nueva. */
  clear(): void {
    this.messages.set([]);
  }
}
