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

  get conversationId(): string {
    return this._conversationId;
  }

  greet(name: string): void {
    this.messages.set([{
      from: 'agent',
      text: `Hola ${name}, soy tu asistente. Puedo informarte de tus envíos, cancelar pedidos o abrir una incidencia.`,
      at: new Date(),
    }]);
  }

  send(text: string): void {
    const trimmed = text.trim();
    if (this.sending() || !trimmed) return;
    this.messages.update(m => [...m, { from: 'user', text: trimmed, at: new Date() }]);
    this.sending.set(true);

    this.api.send({ conversationId: this._conversationId, message: trimmed })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: r => this.messages.update(m => [
          ...m,
          { from: 'agent', text: r.reply, intent: r.intent, at: new Date() },
        ]),
        error: () => this.messages.update(m => [
          ...m,
          {
            from: 'agent',
            text: 'Ahora mismo no puedo atenderte, inténtalo de nuevo en unos minutos.',
            at: new Date(),
          },
        ]),
      });
  }

  newConversation(name: string): void {
    this._conversationId = crypto.randomUUID();
    this.messages.set([]);
    this.greet(name);
  }
}
