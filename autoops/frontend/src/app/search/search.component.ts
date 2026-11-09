import { CommonModule } from '@angular/common';
import { AfterViewChecked, Component, ElementRef, ViewChild, effect, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SearchStore } from './search-store';
import { AnswerBubbleComponent } from './answer-bubble.component';
import { TypingIndicatorComponent } from './typing-indicator.component';

@Component({
  selector: 'app-search',
  standalone: true,
  imports: [CommonModule, FormsModule, AnswerBubbleComponent, TypingIndicatorComponent],
  providers: [SearchStore],
  templateUrl: './search.component.html',
  styleUrl: './search.component.css',
})
export class SearchComponent implements AfterViewChecked {
  readonly store = inject(SearchStore);
  draft = '';

  readonly examples = [
    '¿Cómo configuramos readiness y liveness según la guía interna?',
    '¿Cuál es el estándar de fragmentación (chunking)?',
    '¿Qué formato deben llevar los logs en producción?',
  ];

  @ViewChild('scroller') private scroller?: ElementRef<HTMLElement>;
  private shouldAutoScroll = false;

  constructor() {
    effect(() => {
      this.store.messages();
      this.store.searching();
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
    if (!this.draft.trim() || this.store.searching()) return;
    this.store.search(this.draft);
    this.draft = '';
  }

  ask(example: string): void {
    if (this.store.searching()) return;
    this.store.search(example);
  }
}
