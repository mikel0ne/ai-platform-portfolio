import { Component, ChangeDetectionStrategy } from '@angular/core';

@Component({
  selector: 'app-typing-indicator',
  standalone: true,
  template: `
    <div class="typing" aria-label="Buscando en la documentación">
      <span></span><span></span><span></span>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.Eager,
  styles: [`
    .typing {
      align-self: flex-start;
      background: #f1f3f5;
      padding: 0.7rem 0.9rem;
      border-radius: 14px;
      border-bottom-left-radius: 4px;
      display: inline-flex;
      gap: 4px;
      margin-bottom: 0.5rem;
    }
    .typing span {
      width: 6px; height: 6px;
      background: #9ca3af;
      border-radius: 50%;
      animation: blink 1.2s infinite ease-in-out;
    }
    .typing span:nth-child(2) { animation-delay: 0.2s; }
    .typing span:nth-child(3) { animation-delay: 0.4s; }
    @keyframes blink {
      0%, 80%, 100% { opacity: 0.3; transform: translateY(0); }
      40% { opacity: 1; transform: translateY(-2px); }
    }
  `],
})
export class TypingIndicatorComponent {}
