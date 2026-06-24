import { CommonModule } from '@angular/common';
import { Component, computed, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, switchMap, startWith } from 'rxjs';
import { ChatApiService } from '../core/chat-api.service';
import { TicketDetail } from '../core/models';

type State =
  | { kind: 'loading' }
  | { kind: 'ok'; ticket: TicketDetail }
  | { kind: 'error' };

@Component({
  selector: 'app-ticket-detail',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <main class="ticket-page">
      @if (ticket(); as t) {
        <article class="card">
          <header class="head">
            <span class="code">[{{ t.code }}]</span>
            <span class="status" [class]="'status-' + t.status.toLowerCase()">
              {{ statusLabel(t.status) }}
            </span>
          </header>

          <h1 class="title">{{ t.title }}</h1>
          <p class="created">Creado el {{ t.createdAt | date:'dd/MM/yyyy HH:mm' }}</p>

          @if (t.orderId != null) {
            <p class="order">Pedido asociado: <strong>#{{ t.orderId }}</strong></p>
          }

          <section class="message">
            <h2>Descripción de la incidencia</h2>
            <p>{{ t.message }}</p>
          </section>
        </article>
      } @else if (loading()) {
        <p class="loading">Cargando ticket…</p>
      } @else {
        <div class="card error">
          <h1>Ticket no encontrado</h1>
          <p>No hemos podido encontrar el ticket solicitado. Puede que el identificador no exista.</p>
        </div>
      }

      <aside class="examples">
        <h2>Tickets de ejemplo</h2>
        <table>
          <thead>
            <tr><th>ID</th><th>Código</th><th>Ir al ticket</th></tr>
          </thead>
          <tbody>
            @for (id of exampleIds; track id) {
              <tr>
                <td>{{ id }}</td>
                <td>[SUP-{{ pad(id) }}]</td>
                <td><a [routerLink]="['/tickets', id]">/tickets/{{ id }}</a></td>
              </tr>
            }
          </tbody>
        </table>
      </aside>
    </main>
  `,
  styles: [`
    :host { display: block; }
    .ticket-page {
      max-width: 560px;
      margin: 0 auto;
      padding: 1.5rem 1.25rem 2rem;
      font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
      color: #1f2328;
    }
    .loading { color: #656d76; }
    .card {
      background: #fff;
      border: 1px solid #e1e4e8;
      border-radius: 14px;
      padding: 1.25rem 1.4rem;
      box-shadow: 0 2px 10px rgba(0,0,0,0.05);
    }
    .card.error h1 { color: #b91c1c; font-size: 1.15rem; }
    .head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.5rem;
    }
    .code {
      font-size: 0.85rem;
      font-weight: 700;
      color: #6d28d9;
      letter-spacing: 0.04em;
    }
    .status {
      font-size: 0.7rem;
      font-weight: 700;
      padding: 2px 10px;
      border-radius: 20px;
      letter-spacing: 0.03em;
      text-transform: uppercase;
    }
    .status-open        { background: #dbeafe; color: #1d4ed8; }
    .status-in_progress { background: #fef3c7; color: #b45309; }
    .status-closed      { background: #d1fae5; color: #065f46; }
    .title { font-size: 1.25rem; margin: 0.7rem 0 0.25rem; }
    .created { color: #656d76; font-size: 0.8rem; margin: 0 0 0.5rem; }
    .order {
      font-size: 0.85rem;
      margin: 0 0 1rem;
      color: #1f2328;
    }
    .order strong { color: #1d4ed8; }
    .message h2 {
      font-size: 0.8rem;
      text-transform: uppercase;
      letter-spacing: 0.04em;
      color: #656d76;
      margin: 0 0 0.35rem;
    }
    .message p { line-height: 1.5; white-space: pre-wrap; margin: 0; }

    .examples {
      margin-top: 1.5rem;
      border-top: 1px dashed #d8d8d8;
      padding-top: 1rem;
    }
    .examples h2 { font-size: 0.95rem; margin: 0 0 0.6rem; }
    table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
    th, td { text-align: left; padding: 0.4rem 0.5rem; border-bottom: 1px solid #eee; }
    th { color: #656d76; font-weight: 600; }
    a { color: #7c3aed; }
  `],
})
export class TicketDetailComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(ChatApiService);

  readonly exampleIds = [1, 2, 3];

  readonly state = toSignal(
    this.route.paramMap.pipe(
      map(p => Number(p.get('id'))),
      switchMap(id =>
        this.api.ticket(id).pipe(
          map(ticket => ({ kind: 'ok', ticket }) as State),
          startWith({ kind: 'loading' } as State),
          catchError(() => of({ kind: 'error' } as State)),
        ),
      ),
    ),
    { initialValue: { kind: 'loading' } as State },
  );

  readonly ticket = computed(() => {
    const s = this.state();
    return s.kind === 'ok' ? s.ticket : null;
  });

  readonly loading = computed(() => this.state().kind === 'loading');

  statusLabel(status: string): string {
    const labels: Record<string, string> = {
      OPEN:        'Abierto',
      IN_PROGRESS: 'En curso',
      CLOSED:      'Cerrado',
    };
    return labels[status] ?? status;
  }

  pad(id: number): string {
    return String(id).padStart(4, '0');
  }
}
