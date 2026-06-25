import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';
import { SearchMessage } from '../core/models';

/**
 * Burbuja de un mensaje del hilo de búsqueda. Para las respuestas del asistente
 * muestra, además del texto redactado, el nivel de confianza y las citas (fuente +
 * sección + score) que respaldan la respuesta — el valor diferencial frente a un chat
 * que alucina (docs/arquitectura/05-contratos-api-datos.md §2).
 */
@Component({
  selector: 'app-answer-bubble',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="bubble"
         [class.user]="msg.from === 'user'"
         [class.agent]="msg.from === 'agent'"
         [class.error]="msg.error">
      <div class="text">{{ msg.text }}</div>

      @if (msg.from === 'agent' && !msg.error) {
        @if (msg.confianza) {
          <div class="confidence" [class]="'conf-' + msg.confianza">
            @if (msg.confianza === 'alta') {
              <span class="dot" aria-hidden="true">●</span> Confianza alta
            } @else {
              <span class="dot" aria-hidden="true">●</span> Confianza baja · contrasta antes de aplicar
            }
          </div>
        }

        @if (msg.citas && msg.citas.length > 0) {
          <div class="citations">
            <div class="citations-head">Fuentes</div>
            @for (cita of msg.citas; track $index) {
              <div class="citation">
                <span class="cite-src">{{ cita.fuente }}</span>
                @if (cita.seccion) {
                  <span class="cite-sep">›</span>
                  <span class="cite-sec">{{ cita.seccion }}</span>
                }
                <span class="cite-score" [title]="'Score de recuperación'">{{ cita.score | number:'1.2-2' }}</span>
              </div>
            }
          </div>
        }
      }

      <div class="meta">
        <span class="time">{{ msg.at | date:'HH:mm' }}</span>
      </div>
    </div>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; }
    .bubble {
      max-width: 88%;
      padding: 0.6rem 0.85rem;
      border-radius: 14px;
      margin-bottom: 0.6rem;
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
    .error {
      background: #fef2f2;
      border: 1px solid #fecaca;
      color: #b91c1c;
    }
    .text { white-space: pre-wrap; line-height: 1.45; }

    /* ── Confianza ───────────────────────────────────────────────────────── */
    .confidence {
      display: inline-flex;
      align-items: center;
      gap: 0.35rem;
      margin-top: 0.6rem;
      font-size: 0.72rem;
      font-weight: 700;
      padding: 2px 9px;
      border-radius: 20px;
      letter-spacing: 0.02em;
    }
    .confidence .dot { font-size: 0.6rem; }
    .conf-alta { background: #d1fae5; color: #065f46; }
    .conf-baja { background: #fef3c7; color: #b45309; }

    /* ── Citas ───────────────────────────────────────────────────────────── */
    .citations {
      margin-top: 0.6rem;
      padding-top: 0.5rem;
      border-top: 1px solid #e1e4e8;
      display: flex;
      flex-direction: column;
      gap: 0.3rem;
    }
    .citations-head {
      font-size: 0.66rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: #656d76;
    }
    .citation {
      display: flex;
      align-items: center;
      gap: 0.35rem;
      font-size: 0.78rem;
      min-width: 0;
    }
    .cite-src {
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-weight: 600;
      color: #0969da;
      white-space: nowrap;
    }
    .cite-sep { color: #afb8c1; flex-shrink: 0; }
    .cite-sec {
      color: #57606a;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .cite-score {
      margin-left: auto;
      flex-shrink: 0;
      font-variant-numeric: tabular-nums;
      font-size: 0.7rem;
      font-weight: 700;
      color: #57606a;
      background: #fff;
      border: 1px solid #d0d7de;
      border-radius: 20px;
      padding: 0 7px;
    }

    /* ── Meta ────────────────────────────────────────────────────────────── */
    .meta {
      margin-top: 0.35rem;
      font-size: 0.7rem;
      opacity: 0.75;
    }
    .user .meta { text-align: right; }
  `],
})
export class AnswerBubbleComponent {
  @Input({ required: true }) msg!: SearchMessage;
}
