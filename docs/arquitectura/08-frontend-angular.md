# 08 — Frontend Angular

SPA mínima pero cuidada: una interfaz de chat con "login" simulado. Su único trabajo es conversar con `POST /api/copilot/chat`; toda la inteligencia vive en el backend.

## Decisiones

| Tema | Decisión |
|---|---|
| Versión | Angular 17+ (standalone components, signals, control flow `@if/@for`) |
| Ubicación | carpeta `frontend/` dentro de este mismo repo (prototipo monorepo; simplifica la demo y el despliegue conjunto) |
| Estado | servicios con signals; sin NgRx (sobredimensionado aquí) |
| Estilos | CSS propio o Angular Material (a gusto); diseño tipo chat clásico |
| Comunicación | `HttpClient` + proxy de desarrollo (`/api` → `localhost:8080`); sin CORS |
| Login | selector de usuario de demo (datos de `GET /api/copilot/users`); el elegido se guarda en `sessionStorage` y se envía como cabecera `X-User-Id` |

## Estructura

```
frontend/
├── proxy.conf.json                  # { "/api": { "target": "http://localhost:8080" } }
├── src/app/
│   ├── app.component.ts             # shell: header con usuario activo + router-outlet
│   ├── app.routes.ts                # '' → LoginComponent, 'chat' → ChatComponent (guard: hay usuario)
│   ├── core/
│   │   ├── auth.service.ts          # usuario simulado (signal<UserDto|null>, sessionStorage)
│   │   ├── auth.interceptor.ts      # añade X-User-Id a toda petición /api
│   │   └── chat-api.service.ts      # POST /api/copilot/chat, GET /api/copilot/users
│   ├── login/
│   │   └── login.component.ts       # tarjetas de usuarios demo → "entrar"
│   └── chat/
│       ├── chat.component.ts        # orquesta la conversación
│       ├── chat-store.ts            # signals: messages[], sending, conversationId
│       ├── message-bubble.component.ts
│       └── typing-indicator.component.ts
```

## Modelo y servicios

```ts
// core/models.ts
export interface UserDto { id: string; name: string; }
export interface ChatRequest { conversationId: string; message: string; }
export interface ChatResponse {
  conversationId: string;
  reply: string;
  intent: 'TRACKING' | 'CANCEL' | 'SUPPORT' | 'OTHER';
  resolved: boolean;
}
export interface ChatMessage {            // modelo de UI
  from: 'user' | 'agent';
  text: string;
  intent?: ChatResponse['intent'];
  at: Date;
}
```

```ts
// core/chat-api.service.ts
@Injectable({ providedIn: 'root' })
export class ChatApiService {
  private http = inject(HttpClient);

  users(): Observable<UserDto[]> {
    return this.http.get<UserDto[]>('/api/copilot/users');
  }

  send(req: ChatRequest): Observable<ChatResponse> {
    return this.http.post<ChatResponse>('/api/copilot/chat', req);
  }
}
```

```ts
// core/auth.interceptor.ts — el "token de sesión" simulado
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const user = inject(AuthService).user();
  return next(user ? req.clone({ setHeaders: { 'X-User-Id': user.id } }) : req);
};
```

## Lógica de conversación

```ts
// chat/chat-store.ts
@Injectable()
export class ChatStore {
  private api = inject(ChatApiService);

  readonly messages = signal<ChatMessage[]>([]);
  readonly sending = signal(false);
  readonly conversationId = crypto.randomUUID();   // ← threadId del grafo, uno por sesión de chat

  send(text: string) {
    if (this.sending() || !text.trim()) return;
    this.messages.update(m => [...m, { from: 'user', text, at: new Date() }]);
    this.sending.set(true);                        // bloquea el input: 1 turno en vuelo (ver doc 06)

    this.api.send({ conversationId: this.conversationId, message: text })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: r => this.messages.update(m =>
          [...m, { from: 'agent', text: r.reply, intent: r.intent, at: new Date() }]),
        error: () => this.messages.update(m =>
          [...m, { from: 'agent', text: 'Ahora mismo no puedo atenderte, inténtalo de nuevo en unos minutos.', at: new Date() }]),
      });
  }

  newConversation() { /* regenera conversationId y limpia messages */ }
}
```

Detalles de UX que venden el prototipo:

- **Indicador "escribiendo…"** mientras `sending()` (el turno tarda 2-4 s por las llamadas a Gemini).
- **Badge de intención** discreto en cada burbuja del agente (`TRACKING`, `CANCEL`…) — hace visible el enrutamiento semántico en la demo.
- **Botón "Nueva conversación"** (regenera el `conversationId` → estado del grafo limpio).
- **Mensaje de bienvenida** local (no consume LLM): "Hola {{name}}, soy tu asistente. Puedo informarte de tus envíos, cancelar pedidos o abrir una incidencia."
- Las saltos de línea del agente se respetan (`white-space: pre-wrap`).

## Arranque en desarrollo

```bash
cd frontend
npm install
ng serve --proxy-config proxy.conf.json   # http://localhost:4200, /api → :8080
```

Backend en paralelo: `mvn spring-boot:run` con `GEMINI_API_KEY` exportada.

## Build de producción

`ng build` genera estáticos que sirve un **nginx** en Kubernetes (imagen y manifiestos en la rama `docs/infraestructura-k8s`). El nginx hace también de reverse-proxy de `/api` hacia el servicio del backend, de modo que la SPA usa rutas relativas y no necesita configuración por entorno.

## Tests (mínimos exigibles)

- `ChatStore`: al enviar, añade mensaje del usuario, bloquea input, añade respuesta del agente (HttpTestingController).
- `authInterceptor`: añade `X-User-Id` cuando hay usuario.
- Smoke e2e manual guiado por la demo de los 3 escenarios (guion en el [plan, Fase 6](09-plan-implementacion.md)).
