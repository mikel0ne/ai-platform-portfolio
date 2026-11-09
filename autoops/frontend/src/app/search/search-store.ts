import { Injectable, inject, signal } from '@angular/core';
import { finalize } from 'rxjs/operators';
import { SearchApiService } from '../core/search-api.service';
import { SearchMessage } from '../core/models';

// Stateless on purpose: every query is independent, nothing is persisted.
@Injectable()
export class SearchStore {
  private readonly api = inject(SearchApiService);

  readonly messages = signal<SearchMessage[]>([]);
  readonly searching = signal(false);

  search(query: string): void {
    const message = query.trim();
    if (this.searching() || !message) return;

    this.messages.update(m => [...m, { from: 'user', text: message, at: new Date() }]);
    this.searching.set(true);

    this.api.search({ query: message })
      .pipe(finalize(() => this.searching.set(false)))
      .subscribe({
        next: r => this.messages.update(m => [
          ...m,
          { from: 'agent', text: r.answer, citations: r.citations, confidence: r.confidence, at: new Date() },
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

  clear(): void {
    this.messages.set([]);
  }
}
