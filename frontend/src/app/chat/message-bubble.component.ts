import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';
import { ChatMessage } from '../core/models';

@Component({
  selector: 'app-message-bubble',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="bubble" [class.user]="msg.from === 'user'" [class.agent]="msg.from === 'agent'">
      <div class="text">{{ msg.text }}</div>
      <div class="meta">
        @if (msg.intent && msg.intent !== 'NONE' && msg.from === 'agent') {
          <span class="badge" [class]="'intent-' + msg.intent.toLowerCase()">{{ msg.intent }}</span>
        }
        <span class="time">{{ msg.at | date:'HH:mm' }}</span>
      </div>
    </div>
  `,
  styles: [`
    .bubble {
      max-width: 78%;
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
    .intent-cancel { background: #fee2e2; color: #b91c1c; }
    .intent-support { background: #ede9fe; color: #6d28d9; }
    .intent-other { background: #e5e7eb; color: #374151; }
  `],
})
export class MessageBubbleComponent {
  @Input({ required: true }) msg!: ChatMessage;
}
