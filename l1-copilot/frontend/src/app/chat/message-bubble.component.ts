import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { ChatMessage, OrderCard, ReturnCard, TicketCard } from '../core/models';

@Component({
  selector: 'app-message-bubble',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="bubble" [class.user]="msg.from === 'user'" [class.agent]="msg.from === 'agent'">
      <div class="text">{{ msg.text }}</div>

      @if (msg.orders && msg.orders.length > 0) {
        <div class="order-cards">
          @for (order of msg.orders; track order.id) {
            @if (msg.ordersSelectable) {
              <button class="order-card" (click)="selectOrder(order)">
                <ng-container *ngTemplateOutlet="orderCardBody; context: { $implicit: order }"></ng-container>
              </button>
            } @else {
              <div class="order-card static">
                <ng-container *ngTemplateOutlet="orderCardBody; context: { $implicit: order }"></ng-container>
              </div>
            }
          }
        </div>
      }

      <ng-template #orderCardBody let-order>
        <div class="card-img-wrap">
          @if (order.imageUrl) {
            <img [src]="order.imageUrl" [alt]="order.description" class="card-img" loading="lazy" />
          } @else {
            <div class="card-img-placeholder">📦</div>
          }
        </div>
        <div class="card-body">
          <span class="card-order-num">#{{ order.id }}</span>
          <span class="card-desc">{{ order.description }}</span>
          <span class="card-status" [class]="'status-' + order.status.toLowerCase()">
            {{ statusLabel(order.status) }}
          </span>
        </div>
      </ng-template>

      @if (msg.tickets && msg.tickets.length > 0) {
        <div class="ticket-cards">
          @for (ticket of msg.tickets; track ticket.id) {
            <a class="ticket-link" href="javascript:void(0)" (click)="openTicket(ticket)">
              <span class="ticket-icon">🎫</span>
              <span class="ticket-body">
                <span class="ticket-code">[{{ ticket.code }}]</span>
                <span class="ticket-title">{{ ticket.title }}</span>
                <span class="ticket-status" [class]="'tstatus-' + ticket.status.toLowerCase()">
                  {{ ticketStatusLabel(ticket.status) }}
                </span>
              </span>
              <span class="ticket-open" aria-hidden="true">↗</span>
            </a>
          }
        </div>
      }

      @if (msg.returns && msg.returns.length > 0) {
        <div class="return-cards">
          @for (ret of msg.returns; track ret.id) {
            <a class="return-link" href="javascript:void(0)" (click)="openReturn(ret)">
              <span class="return-icon">🏷️</span>
              <span class="return-body">
                <span class="return-code">[{{ ret.code }}]</span>
                <span class="return-title">Etiqueta de devolución · {{ ret.description }}</span>
                <span class="return-status">Devolución iniciada</span>
              </span>
              <span class="return-open" aria-hidden="true">↗</span>
            </a>
          }
        </div>
      }

      @if (msg.confirm) {
        <div class="confirm-action">
          <p class="confirm-title">{{ msg.confirm.title }}</p>
          <p class="confirm-warning">⚠️ {{ msg.confirm.warning }}</p>
          <div class="confirm-buttons">
            <button type="button" class="btn-danger"
                    (click)="triggerAction(msg.confirm.confirmPayload, msg.confirm.confirmLabel)">
              {{ msg.confirm.confirmLabel }}
            </button>
            <button type="button" class="btn-secondary"
                    (click)="triggerAction(msg.confirm.cancelPayload, msg.confirm.cancelLabel)">
              {{ msg.confirm.cancelLabel }}
            </button>
          </div>
        </div>
      }

      <div class="meta">
        @if (msg.intent && msg.intent !== 'NONE' && msg.from === 'agent') {
          <span class="badge" [class]="'intent-' + msg.intent.toLowerCase()">{{ msg.intent }}</span>
        }
        <span class="time">{{ msg.at | date:'HH:mm' }}</span>
      </div>
    </div>
  `,
  styles: [`
    :host {
      display: flex;
      flex-direction: column;
    }
    .bubble {
      max-width: 82%;
      padding: 0.6rem 0.85rem;
      border-radius: 14px;
      margin-bottom: 0.5rem;
      box-shadow: 0 1px 2px rgba(0,0,0,0.05);
    }
    .user {
      align-self: flex-end;
      background: #1f6feb;
      color: #fff;
      border-bottom-right-radius: 4px;
    }
    .agent {
      align-self: flex-start;
      background: #f1f3f5;
      color: #1f2328;
      border-bottom-left-radius: 4px;
    }
    .text { white-space: pre-wrap; line-height: 1.4; }

    /* ── Order cards ─────────────────────────────────────────────────────── */
    .order-cards {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      margin-top: 0.65rem;
    }
    .order-card {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      background: #fff;
      border: 1.5px solid #e1e4e8;
      border-radius: 10px;
      padding: 0.5rem 0.75rem;
      cursor: pointer;
      text-align: left;
      width: 100%;
      transition: border-color 0.15s, box-shadow 0.15s, transform 0.1s;
    }
    .order-card:hover {
      border-color: #1f6feb;
      box-shadow: 0 2px 8px rgba(31,111,235,0.15);
      transform: translateY(-1px);
    }
    .order-card:active { transform: translateY(0); }

    /* Tarjeta de pedido en foco (detalle, no listado): informativa, no clicable. */
    .order-card.static {
      cursor: default;
    }
    .order-card.static:hover {
      border-color: #e1e4e8;
      box-shadow: none;
      transform: none;
    }
    .order-card.static:active { transform: none; }

    .card-img-wrap {
      flex-shrink: 0;
      width: 60px;
      height: 48px;
      border-radius: 6px;
      overflow: hidden;
      background: #f6f8fa;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .card-img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
    .card-img-placeholder { font-size: 1.6rem; }

    .card-body {
      display: flex;
      flex-direction: column;
      gap: 2px;
      min-width: 0;
    }
    .card-order-num {
      font-size: 0.7rem;
      font-weight: 700;
      color: #656d76;
      letter-spacing: 0.03em;
    }
    .card-desc {
      font-size: 0.82rem;
      font-weight: 600;
      color: #1f2328;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .card-status {
      display: inline-block;
      font-size: 0.68rem;
      font-weight: 700;
      padding: 1px 7px;
      border-radius: 20px;
      align-self: flex-start;
      letter-spacing: 0.03em;
      text-transform: uppercase;
    }
    .status-created    { background: #e5e7eb; color: #374151; }
    .status-paid       { background: #dbeafe; color: #1d4ed8; }
    .status-shipped    { background: #ffedd5; color: #c2410c; }
    .status-in_delivery { background: #ede9fe; color: #6d28d9; }
    .status-delivered  { background: #d1fae5; color: #065f46; }
    .status-cancelled  { background: #fee2e2; color: #b91c1c; }
    .status-return_requested { background: #ccfbf1; color: #0f766e; }

    /* ── Ticket link card ────────────────────────────────────────────────── */
    .ticket-cards {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      margin-top: 0.65rem;
    }
    .ticket-link {
      display: flex;
      align-items: center;
      gap: 0.7rem;
      background: #fff;
      border: 1.5px solid #d8b4fe;
      border-left: 4px solid #7c3aed;
      border-radius: 10px;
      padding: 0.55rem 0.75rem;
      text-decoration: none;
      cursor: pointer;
      transition: border-color 0.15s, box-shadow 0.15s, transform 0.1s;
    }
    .ticket-link:hover {
      box-shadow: 0 2px 8px rgba(124,58,237,0.18);
      transform: translateY(-1px);
    }
    .ticket-link:active { transform: translateY(0); }
    .ticket-icon { font-size: 1.3rem; flex-shrink: 0; }
    .ticket-body {
      display: flex;
      flex-direction: column;
      gap: 2px;
      min-width: 0;
      flex: 1;
    }
    .ticket-code {
      font-size: 0.72rem;
      font-weight: 700;
      color: #6d28d9;
      letter-spacing: 0.04em;
    }
    .ticket-title {
      font-size: 0.84rem;
      font-weight: 600;
      color: #1f2328;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .ticket-status {
      display: inline-block;
      font-size: 0.66rem;
      font-weight: 700;
      padding: 1px 7px;
      border-radius: 20px;
      align-self: flex-start;
      letter-spacing: 0.03em;
      text-transform: uppercase;
    }
    .tstatus-open        { background: #dbeafe; color: #1d4ed8; }
    .tstatus-in_progress { background: #fef3c7; color: #b45309; }
    .tstatus-closed      { background: #d1fae5; color: #065f46; }
    .ticket-open {
      font-size: 1.1rem;
      color: #7c3aed;
      flex-shrink: 0;
    }

    /* ── Return label card ───────────────────────────────────────────────── */
    .return-cards {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      margin-top: 0.65rem;
    }
    .return-link {
      display: flex;
      align-items: center;
      gap: 0.7rem;
      background: #fff;
      border: 1.5px solid #99f6e4;
      border-left: 4px solid #0d9488;
      border-radius: 10px;
      padding: 0.55rem 0.75rem;
      text-decoration: none;
      cursor: pointer;
      transition: border-color 0.15s, box-shadow 0.15s, transform 0.1s;
    }
    .return-link:hover {
      box-shadow: 0 2px 8px rgba(13,148,136,0.18);
      transform: translateY(-1px);
    }
    .return-link:active { transform: translateY(0); }
    .return-icon { font-size: 1.3rem; flex-shrink: 0; }
    .return-body {
      display: flex;
      flex-direction: column;
      gap: 2px;
      min-width: 0;
      flex: 1;
    }
    .return-code {
      font-size: 0.72rem;
      font-weight: 700;
      color: #0f766e;
      letter-spacing: 0.04em;
    }
    .return-title {
      font-size: 0.84rem;
      font-weight: 600;
      color: #1f2328;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .return-status {
      display: inline-block;
      font-size: 0.66rem;
      font-weight: 700;
      padding: 1px 7px;
      border-radius: 20px;
      align-self: flex-start;
      letter-spacing: 0.03em;
      text-transform: uppercase;
      background: #ccfbf1;
      color: #0f766e;
    }
    .return-open {
      font-size: 1.1rem;
      color: #0d9488;
      flex-shrink: 0;
    }

    /* ── Meta row ────────────────────────────────────────────────────────── */
    .meta {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin-top: 0.3rem;
      font-size: 0.72rem;
      opacity: 0.8;
    }
    .user .meta { justify-content: flex-end; }
    .badge {
      display: inline-block;
      padding: 1px 6px;
      border-radius: 6px;
      font-weight: 600;
      letter-spacing: 0.02em;
    }
    .intent-tracking { background: #dbeafe; color: #1d4ed8; }
    .intent-cancel   { background: #fee2e2; color: #b91c1c; }
    .intent-return   { background: #ccfbf1; color: #0f766e; }
    .intent-support  { background: #ede9fe; color: #6d28d9; }
    .intent-tickets  { background: #f3e8ff; color: #7c3aed; }
    .intent-faq      { background: #cffafe; color: #0e7490; }
    .intent-chatty   { background: #fce7f3; color: #be185d; }
    .intent-other    { background: #e5e7eb; color: #374151; }

    .confirm-action {
      margin-top: 10px;
      padding: 12px;
      border: 1.5px solid #fecaca;
      border-radius: 10px;
      background: #fef2f2;
    }
    .confirm-title { margin: 0 0 4px; font-weight: 600; color: #1f2328; font-size: 0.92rem; }
    .confirm-warning { margin: 0 0 10px; color: #b91c1c; font-size: 0.82rem; }
    .confirm-buttons { display: flex; gap: 8px; flex-wrap: wrap; }
    .confirm-buttons button {
      flex: 1 1 auto;
      padding: 8px 12px;
      border-radius: 8px;
      font-weight: 600;
      font-size: 0.88rem;
      cursor: pointer;
      border: 1.5px solid transparent;
      transition: filter 0.15s, background 0.15s;
    }
    .btn-danger { background: #dc2626; color: #fff; }
    .btn-danger:hover { filter: brightness(1.08); }
    .btn-secondary { background: #fff; color: #374151; border-color: #d1d5db; }
    .btn-secondary:hover { background: #f3f4f6; }
  `],
})
export class MessageBubbleComponent {
  @Input({ required: true }) msg!: ChatMessage;
  @Output() orderSelected = new EventEmitter<OrderCard>();
  @Output() actionTriggered = new EventEmitter<{ payload: string; label: string }>();

  selectOrder(order: OrderCard): void {
    this.orderSelected.emit(order);
  }

  /** Pulsación de un botón de acción: envía el comando al backend mostrando su etiqueta. */
  triggerAction(payload: string, label: string): void {
    this.actionTriggered.emit({ payload, label });
  }

  /** Abre el detalle del ticket en una ventana emergente. */
  openTicket(ticket: TicketCard): void {
    const features = 'width=520,height=680,menubar=no,toolbar=no,location=no,resizable=yes,scrollbars=yes';
    window.open(`/tickets/${ticket.id}`, `ticket-${ticket.id}`, features);
  }

  /** Abre la etiqueta de devolución en una ventana emergente. */
  openReturn(ret: ReturnCard): void {
    const features = 'width=520,height=720,menubar=no,toolbar=no,location=no,resizable=yes,scrollbars=yes';
    window.open(`/returns/${ret.id}`, `return-${ret.id}`, features);
  }

  ticketStatusLabel(status: string): string {
    const labels: Record<string, string> = {
      OPEN:        'Abierto',
      IN_PROGRESS: 'En curso',
      CLOSED:      'Cerrado',
    };
    return labels[status] ?? status;
  }

  statusLabel(status: string): string {
    const labels: Record<string, string> = {
      CREATED:     'Creado',
      PAID:        'Pagado',
      SHIPPED:     'Enviado',
      IN_DELIVERY: 'En reparto',
      DELIVERED:   'Entregado',
      CANCELLED:   'Cancelado',
      RETURN_REQUESTED: 'En devolución',
    };
    return labels[status] ?? status;
  }
}
