import { CommonModule } from '@angular/common';
import { Component, Input, ChangeDetectionStrategy } from '@angular/core';
import { MarkdownComponent } from 'ngx-markdown';
import { Origin, SearchMessage } from '../core/models';

type ScoreLevel = 'top' | 'mid' | 'low';

@Component({
  selector: 'app-answer-bubble',
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
        @if (msg.confidence) {
          <div class="confidence" [class]="'conf-' + msg.confidence">
            @switch (msg.confidence) {
              @case ('high') {
                <span class="icon" aria-hidden="true">✓</span> Confianza alta
              }
              @case ('medium') {
                <span class="icon" aria-hidden="true">❓</span> Cobertura parcial · contrasta antes de aplicar
              }
              @case ('low') {
                <span class="icon" aria-hidden="true">⚠️</span> Información tangencial · verifica antes de aplicar
              }
              @case ('none') {
                <span class="icon" aria-hidden="true">❌</span> Sin documentación interna · verifica obligatoriamente
              }
            }
          </div>
        }

        @if (msg.citations && msg.citations.length > 0) {
          <div class="citations">
            <div class="citations-head">Fuentes</div>
            @for (citation of msg.citations; track $index) {
              <div class="citation">
                <div class="citation-row">
                  <span class="cite-origin" [class]="'origin-' + citation.origin">
                    {{ originLabel(citation.origin) }}
                  </span>
                  @if (citation.url) {
                    <a class="cite-title" [href]="citation.url" target="_blank" rel="noopener noreferrer" [title]="citation.title">{{ citation.source }}</a>
                  } @else {
                    <span class="cite-title" [title]="citation.title">{{ citation.source }}</span>
                  }
                  <span class="cite-score"
                        [class]="'score-' + scoreLevel(citation.score)"
                        title="Score del reranker normalizado a 0-10 (sigmoide); orientativo, no garantiza relevancia real — ver confianza">
                    {{ normalizedScore(citation.score) | number:'1.1-1' }}
                  </span>
                </div>
                @if (citation.section) {
                  <div class="cite-section">{{ citation.section }}</div>
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
  changeDetection: ChangeDetectionStrategy.Eager,
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
    .confidence .icon {
      font-size: 0.8rem;
      height: 18px;
      width: 18px;
      text-align: center;
      border-radius: 50%;
      margin-left: -7px;
    }
    .conf-high   { background: #8fe4a8; color: #065f46; }
    .conf-medium { background: #f1e89d; color: #9b4300; }
    .conf-low    { background: #ffc494; color: #930007; }
    .conf-none   { background: #ffabab; color: #1f2937; }
    .conf-high   .icon { background-color: #daffec; }
    .conf-medium .icon { background-color: #fff6d6; }
    .conf-low    .icon { background-color: #FFE2C4; line-height: 1; }
    .conf-none   .icon { background-color: #fce8e8; }

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
    .score-mid { background: #fef3c7; color: #b45309; }
    .score-low { background: #fff; color: #57606a; border-color: #d0d7de; }
    .cite-section {
      padding-left: 0.1rem;
      color: #57606a;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

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

  private static readonly ORIGIN_LABEL: Record<Origin, string> = {
    git: 'Git',
    wikijs: 'Wiki',
    'markdown-local': 'Local',
  };

  originLabel(origin: Origin): string {
    return AnswerBubbleComponent.ORIGIN_LABEL[origin] ?? origin;
  }

  // With a slope of 1 the observed reranker scores (~3-4) all saturated at 9.5-9.8 and became indistinguishable.
  private static readonly SCORE_TEMPERATURE = 3;

  // The cross-encoder score is an unbounded logit; a sigmoid centred on 0 maps it to 0-10 for display only.
  normalizedScore(score: number): number {
    return 10 / (1 + Math.exp(-score / AnswerBubbleComponent.SCORE_TEMPERATURE));
  }

  scoreLevel(score: number): ScoreLevel {
    const normalized = this.normalizedScore(score);
    if (normalized >= 7) return 'top';
    if (normalized >= 4) return 'mid';
    return 'low';
  }
}
