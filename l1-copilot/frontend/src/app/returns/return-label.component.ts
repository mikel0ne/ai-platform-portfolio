import { CommonModule } from '@angular/common';
import { Component, computed, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, switchMap, startWith } from 'rxjs';
import { ChatApiService } from '../core/chat-api.service';
import { ReturnLabel } from '../core/models';

type State =
  | { kind: 'loading' }
  | { kind: 'ok'; label: ReturnLabel }
  | { kind: 'error' };

@Component({
  selector: 'app-return-label',
  standalone: true,
  imports: [CommonModule],
  template: `
    <main class="return-page">
      @if (label(); as l) {
        <article class="label">
          <header class="head">
            <div class="brand">
              <span class="logo">📦 Tienda Online</span>
              <span class="kind">Etiqueta de devolución</span>
            </div>
            <span class="status">En devolución</span>
          </header>

          <section class="ref">
            <div class="ref-code">
              <span class="ref-label">Referencia de devolución</span>
              <span class="ref-value">{{ l.code }}</span>
            </div>
            <div class="carrier">
              <span class="ref-label">Transportista</span>
              <span class="carrier-value">{{ l.carrier }}</span>
            </div>
          </section>

          <section class="addr">
            <span class="addr-label">Remitir a</span>
            <p class="addr-name">{{ l.destinationName }}</p>
            <p class="addr-line">{{ l.destinationAddress }}</p>
          </section>

          <section class="product">
            <span class="addr-label">Artículo a devolver</span>
            <p class="product-name">{{ l.orderDescription }}</p>
            <p class="order-ref">Pedido original #{{ l.id }}</p>
          </section>

          <section class="barcode">
            <div class="bars">
              @for (w of bars(l.barcode); track $index) {
                <span
                  class="bar"
                  [class.gap]="w.gap"
                  [class.tall]="w.tall"
                  [style.width.px]="w.width"
                ></span>
              }
            </div>
            <span class="barcode-value">{{ l.barcode }}</span>
          </section>

          <footer class="note">
            <p>
              Imprime esta etiqueta, pégala sobre el embalaje original y entrega el
              paquete en cualquier punto del transportista colaborador. La devolución
              es gratuita.
            </p>
          </footer>
        </article>
      } @else if (loading()) {
        <p class="loading">Cargando etiqueta de devolución…</p>
      } @else {
        <div class="label error">
          <h1>Etiqueta no encontrada</h1>
          <p>No hemos podido encontrar la devolución solicitada. Puede que el identificador no exista.</p>
        </div>
      }
    </main>
  `,
  styles: [`
    :host { display: block; }
    .return-page {
      max-width: 520px;
      margin: 0 auto;
      padding: 1.5rem 1.25rem 2rem;
      font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
      color: #1f2328;
    }
    .loading { color: #656d76; }
    .label {
      background: #fff;
      border: 1px solid #e1e4e8;
      border-radius: 14px;
      padding: 1.25rem 1.4rem;
      box-shadow: 0 2px 10px rgba(0,0,0,0.05);
    }
    .label.error h1 { color: #b91c1c; font-size: 1.15rem; }
    .head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 0.5rem;
      border-bottom: 2px dashed #d8d8d8;
      padding-bottom: 0.8rem;
    }
    .brand { display: flex; flex-direction: column; gap: 2px; }
    .logo { font-weight: 700; font-size: 1rem; }
    .kind {
      font-size: 0.72rem;
      text-transform: uppercase;
      letter-spacing: 0.06em;
      color: #656d76;
    }
    .status {
      font-size: 0.7rem;
      font-weight: 700;
      padding: 3px 11px;
      border-radius: 20px;
      letter-spacing: 0.03em;
      text-transform: uppercase;
      background: #ccfbf1;
      color: #0f766e;
      white-space: nowrap;
    }
    .ref {
      display: flex;
      justify-content: space-between;
      gap: 1rem;
      margin-top: 1rem;
    }
    .ref-label {
      display: block;
      font-size: 0.68rem;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: #656d76;
      margin-bottom: 2px;
    }
    .ref-value {
      font-size: 1.05rem;
      font-weight: 700;
      color: #0f766e;
      letter-spacing: 0.04em;
    }
    .carrier { text-align: right; }
    .carrier-value { font-size: 1.05rem; font-weight: 700; }
    .addr, .product { margin-top: 1.1rem; }
    .addr-label {
      display: block;
      font-size: 0.68rem;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: #656d76;
      margin-bottom: 0.25rem;
    }
    .addr-name { font-weight: 700; margin: 0; font-size: 1.05rem; }
    .addr-line { margin: 2px 0 0; line-height: 1.4; }
    .product-name { font-weight: 600; margin: 0; }
    .order-ref { color: #656d76; font-size: 0.82rem; margin: 2px 0 0; }
    .barcode {
      margin-top: 1.4rem;
      padding-top: 1rem;
      border-top: 2px dashed #d8d8d8;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 0.4rem;
    }
    .bars {
      display: flex;
      align-items: flex-start;
      height: 72px;
      padding: 8px 10px 0;
      background: #fff;
    }
    .bar { display: inline-block; height: 60px; background: #1f2328; }
    .bar.tall { height: 72px; }
    .bar.gap { background: transparent; }
    .barcode-value {
      font-family: "Courier New", monospace;
      letter-spacing: 0.45em;
      font-size: 1rem;
      font-weight: 700;
      padding-left: 0.45em;
    }
    .note p {
      margin: 1.2rem 0 0;
      font-size: 0.8rem;
      color: #656d76;
      line-height: 1.5;
    }
  `],
})
export class ReturnLabelComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(ChatApiService);

  readonly state = toSignal(
    this.route.paramMap.pipe(
      map(p => Number(p.get('id'))),
      switchMap(id =>
        this.api.returnLabel(id).pipe(
          map(label => ({ kind: 'ok', label }) as State),
          startWith({ kind: 'loading' } as State),
          catchError(() => of({ kind: 'error' } as State)),
        ),
      ),
    ),
    { initialValue: { kind: 'loading' } as State },
  );

  readonly label = computed(() => {
    const s = this.state();
    return s.kind === 'ok' ? s.label : null;
  });

  readonly loading = computed(() => this.state().kind === 'loading');

  /**
   * Genera las barras de un código de barras EAN-13 real a partir del valor.
   * Cada dígito se codifica en 7 módulos según las tablas L/G/R del estándar,
   * con guardas de inicio (101), central (01010) y final (101) más altas.
   * Si el valor no son 13 dígitos, recurre a un trazado determinista simple.
   */
  bars(value: string): { width: number; gap: boolean; tall: boolean }[] {
    const MODULE = 2; // px por módulo
    const digits = (value ?? '').replace(/\D/g, '');

    if (digits.length !== 13) {
      return this.fallbackBars(value, MODULE);
    }

    // Tablas de codificación EAN-13.
    const L = ['0001101', '0011001', '0010011', '0111101', '0100011',
               '0110001', '0101111', '0111011', '0110111', '0001011'];
    const G = ['0100111', '0110011', '0011011', '0100001', '0011101',
               '0111001', '0000101', '0010001', '0001001', '0010111'];
    const R = ['1110010', '1100110', '1101100', '1000010', '1011100',
               '1001110', '1010000', '1000100', '1001000', '1110100'];
    // Patrón de paridad del lado izquierdo según el primer dígito.
    const PARITY = ['LLLLLL', 'LLGLGG', 'LLGGLG', 'LLGGGL', 'LGLLGG',
                    'LGGLLG', 'LGGGLL', 'LGLGLG', 'LGLGGL', 'LGGLGL'];

    const d = digits.split('').map(Number);
    const out: { width: number; gap: boolean; tall: boolean }[] = [];

    const emit = (modules: string, tall: boolean) => {
      for (const m of modules) {
        out.push({ width: MODULE, gap: m === '0', tall });
      }
    };

    // Guarda de inicio.
    emit('101', true);
    // 6 dígitos izquierdos según la paridad marcada por el primer dígito.
    const parity = PARITY[d[0]];
    for (let i = 0; i < 6; i++) {
      const table = parity[i] === 'L' ? L : G;
      emit(table[d[i + 1]], false);
    }
    // Guarda central.
    emit('01010', true);
    // 6 dígitos derechos (tabla R).
    for (let i = 7; i < 13; i++) {
      emit(R[d[i]], false);
    }
    // Guarda final.
    emit('101', true);

    return out;
  }

  /** Trazado determinista de reserva cuando el valor no es EAN-13 válido. */
  private fallbackBars(value: string, module: number): { width: number; gap: boolean; tall: boolean }[] {
    const out: { width: number; gap: boolean; tall: boolean }[] = [];
    out.push({ width: module, gap: false, tall: true }, { width: module, gap: true, tall: true }, { width: module, gap: false, tall: true });
    for (const ch of value ?? '') {
      const digit = Number(ch) || 0;
      out.push({ width: 1 + (digit % 4), gap: false, tall: false });
      out.push({ width: 1 + ((digit + 1) % 3), gap: true, tall: false });
    }
    out.push({ width: module, gap: false, tall: true }, { width: module, gap: true, tall: true }, { width: module, gap: false, tall: true });
    return out;
  }
}

