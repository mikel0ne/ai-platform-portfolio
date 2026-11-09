import { Injectable, inject, signal } from '@angular/core';
import { finalize } from 'rxjs/operators';
import { ChatApiService } from '../core/chat-api.service';
import { ChatMessage } from '../core/models';

// crypto.randomUUID() needs a secure context and the demo runs on plain http://l1-copilot.local.
function randomConversationId(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, b => b.toString(16).padStart(2, '0'));
  return `${hex.slice(0, 4).join('')}-${hex.slice(4, 6).join('')}-${hex.slice(6, 8).join('')}-${hex.slice(8, 10).join('')}-${hex.slice(10, 16).join('')}`;
}

@Injectable()
export class ChatStore {
  private readonly api = inject(ChatApiService);

  readonly messages = signal<ChatMessage[]>([]);
  readonly sending = signal(false);
  private _conversationId = randomConversationId();
  private formal = false;

  get conversationId(): string {
    return this._conversationId;
  }

  greet(name: string, formal = false): void {
    this.formal = formal;
    const text = formal
      ? `Hola ${name}, soy su asistente. Puedo informarle del estado de sus envíos, cancelar sus pedidos o abrir una incidencia.`
      : `Hola ${name}, soy tu asistente. Puedo informarte del estado de tus envíos, cancelar tus pedidos o abrir una incidencia.`;
    this.messages.set([{ from: 'agent', text, at: new Date() }]);
  }

  // displayText hides the internal command that action buttons send as payload.
  send(payload: string, displayText?: string): void {
    const message = payload.trim();
    if (this.sending() || !message) return;
    const bubble = (displayText ?? payload).trim();
    this.messages.update(m => [
      ...m.map(msg => msg.confirm ? { ...msg, confirm: null } : msg),
      { from: 'user', text: bubble, at: new Date() },
    ]);
    this.sending.set(true);

    this.api.send({ conversationId: this._conversationId, message })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: r => this.messages.update(m => [
          ...m,
          { from: 'agent', text: this.stripThoughts(r.reply), intent: r.intent, at: new Date(), orders: r.orders, ordersSelectable: r.ordersSelectable, tickets: r.tickets, returns: r.returns, confirm: r.confirm },
        ]),
        error: () => this.messages.update(m => [
          ...m,
          {
            from: 'agent',
            text: this.formal
              ? 'Ahora mismo no puedo atenderle, inténtelo de nuevo en unos minutos.'
              : 'Ahora mismo no puedo atenderte, inténtalo de nuevo en unos minutos.',
            at: new Date(),
          },
        ]),
      });
  }

  newConversation(name: string, formal = false): void {
    this._conversationId = randomConversationId();
    this.messages.set([]);
    this.greet(name, formal);
  }

  /** Strips <thought>...</thought> blocks the LLM may include in its reply. */
  private stripThoughts(text: string): string {
    return text.replace(/\s*<thought>[\s\S]*?<\/thought>\s*/gi, '').trim();
  }
}
