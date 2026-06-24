import { CommonModule } from '@angular/common';
import { AfterViewChecked, Component, ElementRef, ViewChild, effect, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../core/auth.service';
import { OrderCard } from '../core/models';
import { ChatStore } from './chat-store';
import { MessageBubbleComponent } from './message-bubble.component';
import { TypingIndicatorComponent } from './typing-indicator.component';

@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [CommonModule, FormsModule, MessageBubbleComponent, TypingIndicatorComponent],
  providers: [ChatStore],
  templateUrl: './chat.component.html',
  styleUrl: './chat.component.css',
})
export class ChatComponent implements AfterViewChecked {
  readonly store = inject(ChatStore);
  private readonly auth = inject(AuthService);
  user = this.auth.user();
  draft = '';

  @ViewChild('scroller') private scroller?: ElementRef<HTMLElement>;
  private shouldAutoScroll = true;

  constructor() {
    this.store.greet(this.user?.name ?? '', this.user?.formal ?? false);
    effect(() => {
      this.store.messages();
      this.store.sending();
      this.shouldAutoScroll = true;
    });
  }

  ngAfterViewChecked(): void {
    if (this.shouldAutoScroll && this.scroller) {
      const el = this.scroller.nativeElement;
      el.scrollTop = el.scrollHeight;
      this.shouldAutoScroll = false;
    }
  }

  send(): void {
    if (!this.draft.trim() || this.store.sending()) return;
    this.store.send(this.draft);
    this.draft = '';
  }

  newConversation(): void {
    this.store.newConversation(this.user?.name ?? '', this.user?.formal ?? false);
  }

  get placeholder(): string {
    return this.user?.formal ? 'Escriba su mensaje…' : 'Escribe tu mensaje…';
  }

  selectOrder(order: OrderCard): void {
    if (this.store.sending()) return;
    this.store.send(`#${order.id}`);
  }

  triggerAction(action: { payload: string; label: string }): void {
    if (this.store.sending()) return;
    this.store.send(action.payload, action.label);
  }
}
