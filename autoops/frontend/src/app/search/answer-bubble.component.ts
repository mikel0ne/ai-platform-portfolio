import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';
import { MarkdownComponent } from 'ngx-markdown';
import { Origen, SearchMessage } from '../core/models';

/** Nivel visual del score normalizado de una cita (ver scoreNormalizado): orientativo, no garantiza relevancia real. */
type Relevancia = 'top' | 'media' | 'baja';

/** Burbuja del hilo de búsqueda: para el asistente añade confianza, citas y respuesta en markdown (docs/arquitectura/05-contratos-api-datos.md §2). */
@Component({
  selector: 'app-answer-bubble',
  standalone: true,
  imports: [CommonModule, MarkdownComponent],
  template: `
    <div class="bubble"
         [class.user]="msg.from === 'user'"
         [class.agent]="msg.from === 'agent'"
         [class.error]="msg.error">
      @if (msg.from === 'agent' && !msg.error) {
        <markdown class="text" [data]="msg.text"></markdown>
      } @else {
        <div class="text">{{ msg.text }}</div>
      }

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
                <div class="citation-row">
                  <span class="cite-origin" [class]="'origin-' + cita.origen">
                    {{ origenLabel(cita.origen) }}
                  </span>
                  @if (cita.url) {
                    <a class="cite-title" [href]="cita.url" target="_blank" rel="noopener noreferrer" [title]="cita.titulo">{{ cita.fuente }}</a>
                  } @else {
                    <span class="cite-title" [title]="cita.titulo">{{ cita.fuente }}</span>
                  }
                  <span class="cite-score"
                        [class]="'score-' + nivelScore(cita.score)"
                        title="Score del reranker normalizado a 0-10 (sigmoide); orientativo, no garantiza relevancia real — ver confianza">
                    {{ scoreNormalizado(cita.score) | number:'1.1-1' }}
                  </span>
                </div>
                @if (cita.seccion) {
                  <div class="cite-section">{{ cita.seccion }}</div>
                }
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
    .text { line-height: 1.45; }
    .text ::ng-deep > *:first-child { margin-top: 0; }
    .text ::ng-deep > *:last-child { margin-bottom: 0; }
    .text ::ng-deep p { margin: 0 0 0.5rem; }
    .text ::ng-deep ul, .text ::ng-deep ol { margin: 0 0 0.5rem; padding-left: 1.2rem; }
    .text ::ng-deep li { margin-bottom: 0.15rem; }
    .text ::ng-deep h1, .text ::ng-deep h2, .text ::ng-deep h3 { margin: 0.5rem 0 0.3rem; font-size: 1em; font-weight: 700; }
    .text ::ng-deep code { font-size: 0.85em; background: rgba(0,0,0,0.07); padding: 1px 4px; border-radius: 4px; }
    .text ::ng-deep pre { background: rgba(0,0,0,0.07); padding: 0.5rem 0.6rem; border-radius: 6px; overflow-x: auto; }
    .text ::ng-deep pre code { background: none; padding: 0; }
    .text ::ng-deep blockquote { margin: 0 0 0.5rem; padding-left: 0.6rem; border-left: 3px solid rgba(0,0,0,0.15); opacity: 0.85; }
    .text ::ng-deep a { color: #0969da; }
    .user .text ::ng-deep a { color: #fff; text-decoration: underline; }

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
      flex-direction: column;
      gap: 0.1rem;
      font-size: 0.78rem;
      min-width: 0;
    }
    .citation-row {
      display: flex;
      align-items: center;
      gap: 0.4rem;
      min-width: 0;
    }
    .cite-origin {
      flex-shrink: 0;
      font-size: 0.62rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.02em;
      padding: 1px 6px;
      border-radius: 20px;
      white-space: nowrap;
    }
    .origin-git { background: #ddf4ff; color: #0969da; }
    .origin-wikijs { background: #e3dafc; color: #6e40c9; }
    .origin-markdown-local { background: #eaeef2; color: #57606a; }
    .cite-title {
      font-weight: 600;
      color: #0969da;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    span.cite-title { color: #57606a; font-weight: 500; }
    .cite-score {
      margin-left: auto;
      flex-shrink: 0;
      font-variant-numeric: tabular-nums;
      font-size: 0.7rem;
      font-weight: 700;
      border-radius: 20px;
      padding: 0 7px;
      border: 1px solid transparent;
    }
    .score-top { background: #d1fae5; color: #065f46; }
    .score-media { background: #fef3c7; color: #b45309; }
    .score-baja { background: #fff; color: #57606a; border-color: #d0d7de; }
    .cite-section {
      padding-left: 0.1rem;
      color: #57606a;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
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

  private static readonly ORIGEN_LABEL: Record<Origen, string> = {
    git: 'GitHub',
    wikijs: 'Wiki',
    'markdown-local': 'Local',
  };

  origenLabel(origen: Origen): string {
    return AnswerBubbleComponent.ORIGEN_LABEL[origen] ?? origen;
  }

  /** Pendiente del sigmoide: con temperatura 1 los scores reales observados (~3-4) saturaban casi todos en 9.5-9.8, indistinguibles. Ajustar si la distribución real en producción resulta muy distinta. */
  private static readonly TEMPERATURA_SCORE = 3;

  /** Comprime el score sin escala fija del reranker (logit, puede ser negativo) a 0-10 vía sigmoide centrado en 0 (la frontera de relevancia al 50% con la que se entrenan estos cross-encoders): acotado y monótono con el original, pero no más fiable como proxy de relevancia que el score crudo. */
  scoreNormalizado(score: number): number {
    return 10 / (1 + Math.exp(-score / AnswerBubbleComponent.TEMPERATURA_SCORE));
  }

  nivelScore(score: number): Relevancia {
    const normalizado = this.scoreNormalizado(score);
    if (normalizado >= 7) return 'top';
    if (normalizado >= 4) return 'media';
    return 'baja';
  }
}
