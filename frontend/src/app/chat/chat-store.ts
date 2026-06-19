import { Injectable, inject, signal } from '@angular/core';
import { finalize } from 'rxjs/operators';
import { ChatApiService } from '../core/chat-api.service';
import { ChatMessage } from '../core/models';

@Injectable()
export class ChatStore {
  private readonly api = inject(ChatApiService);

  readonly messages = signal<ChatMessage[]>([]);
  readonly sending = signal(false);
  private _conversationId = crypto.randomUUID();
  /** true → trato de usted (amable); false → tuteo (simpático). Lo fija el saludo. */
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

  /**
   * Envía un mensaje al agente. El `payload` es lo que viaja al backend; `displayText`
   * (si se indica) es lo que se muestra en la burbuja del usuario — útil para los botones
   * de acción, cuyo payload es un comando interno que el usuario no debería leer.
   */
  send(payload: string, displayText?: string): void {
    const message = payload.trim();
    if (this.sending() || !message) return;
    const bubble = (displayText ?? payload).trim();
    // Al iniciar un nuevo turno, cualquier acción de confirmación pendiente queda superada.
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
          { from: 'agent', text: this.stripThoughts(r.reply), intent: r.intent, at: new Date(), orders: r.orders, tickets: r.tickets, returns: r.returns, confirm: r.confirm },
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
    this._conversationId = crypto.randomUUID();
    this.messages.set([]);
    this.greet(name, formal);
  }

  /** Strips <thought>...</thought> blocks the LLM may include in its reply. */
  private stripThoughts(text: string): string {
    return text.replace(/\s*<thought>[\s\S]*?<\/thought>\s*/gi, '').trim();
  }
}
