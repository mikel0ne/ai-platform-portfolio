# 06 — API REST

API mínima entre el frontend Angular y el agente. Convive con los endpoints ya existentes del proyecto (`/api/llm/**`, `/api/cache/**`); el agente añade su propio namespace `/api/copilot/**`.

## Endpoints

### `POST /api/copilot/chat` — turno de conversación

Petición:

```json
{
  "conversationId": "9b2f6a7e-0c1d-4f7a-9a1e-2b3c4d5e6f70",
  "message": "¿Dónde está mi pedido?"
}
```

Cabecera: `X-User-Id: u-001` (simula el token de sesión; en producción saldría del JWT — ver nota de seguridad).

Respuesta `200 OK`:

```json
{
  "conversationId": "9b2f6a7e-0c1d-4f7a-9a1e-2b3c4d5e6f70",
  "reply": "Veo que tienes dos pedidos recientes: un portátil (#123) y un ratón (#456). ¿Sobre cuál quieres consultar el estado?",
  "intent": "TRACKING",
  "resolved": false
}
```

Semántica de los campos:

| Campo | Significado |
|---|---|
| `conversationId` | UUID generado por el **frontend** en el primer mensaje y repetido en los siguientes; es el `threadId` del checkpointer |
| `reply` | texto del agente para mostrar en el chat |
| `intent` | intención vigente (`TRACKING`/`CANCEL`/`SUPPORT`/`OTHER`) — útil para depurar y para pintar un badge en la UI |
| `resolved` | `true` cuando la gestión del turno cerró el caso (envío informado, pedido cancelado, ticket creado) — la UI puede mostrar "¿algo más?" |

### `GET /api/copilot/users` — usuarios de demo

Devuelve los usuarios del seed para el selector de "login" simulado del frontend:

```json
[
  { "id": "u-001", "name": "Ana García" },
  { "id": "u-002", "name": "Luis Pérez" }
]
```

### Endpoints de apoyo (ya existentes / Actuator)

- `GET /actuator/health` — probes de Kubernetes.
- Swagger UI (`/swagger-ui.html`) documenta todo automáticamente (springdoc ya está en el `pom.xml`).

## DTOs

```java
public record ChatRequest(
        @NotBlank @Size(max = 64) String conversationId,
        @NotBlank @Size(max = 2000) String message) {}

public record ChatResponse(String conversationId, String reply, String intent, boolean resolved) {}

public record UserDto(String id, String name) {}
```

Validación con `jakarta.validation` (`spring-boot-starter-validation`): mensajes vacíos o desmesurados se rechazan con 400 antes de gastar tokens.

## Controlador

```java
// El backend monta TODO bajo /api vía server.servlet.context-path en application.yml.
// Los controllers usan rutas relativas (sin /api), el contexto lo absorbe.
@RestController
@RequestMapping("/copilot")
@RequiredArgsConstructor
@Tag(name = "Copilot", description = "Agente de soporte L1")
public class ChatController {

    private final ConversationService conversationService;
    private final UserRepository userRepository;

    @PostMapping("/chat")
    public ChatResponse chat(@RequestHeader("X-User-Id") String userId,
                             @Valid @RequestBody ChatRequest request) {
        ChatResult result = conversationService.chat(userId, request.conversationId(), request.message());
        return new ChatResponse(request.conversationId(), result.reply(), result.intent(), result.resolved());
    }

    @GetMapping("/users")
    public List<UserDto> demoUsers() {
        return userRepository.findAll().stream()
                .map(u -> new UserDto(u.getId(), u.getName()))
                .toList();
    }
}
```

> **Nota de seguridad:** `X-User-Id` es una simulación asumida y documentada (fuera de alcance la auth real). El diseño deja un único punto de cambio: cuando haya JWT, el `userId` se extraerá del `Authentication` de Spring Security en este mismo controlador y nada más cambia (ni grafo ni tools).

## Manejo de errores (RFC 7807)

`@RestControllerAdvice` global que produce `application/problem+json`:

| Situación | HTTP | `detail` |
|---|---|---|
| Validación de DTO | 400 | mensajes de Bean Validation |
| `X-User-Id` ausente o usuario inexistente | 401 | "Usuario no autenticado" |
| Error interno (BD, grafo) | 500 | "Error procesando el mensaje" (sin detalles internos; se loguean con `conversationId`) |
| Gemini no disponible / cuota | 503 | "El asistente no está disponible, inténtalo en unos minutos" |

```java
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(LlmUnavailableException.class)
    ProblemDetail llmDown(LlmUnavailableException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "El asistente no está disponible, inténtalo en unos minutos");
    }
    // ... resto de handlers
}
```

## Concurrencia y timeouts

- **Un turno por conversación a la vez:** si el usuario envía dos mensajes seguidos, el frontend deshabilita el input hasta recibir respuesta (suficiente para el prototipo; el backend no necesita locking porque el checkpointer serializa por invocación).
- **Timeout del modelo:** configurar el `ChatLanguageModel` de Gemini con `timeout(Duration.ofSeconds(30))` y 1 reintento; el turno completo (router + tools + respond ≈ 2-3 llamadas LLM) debe quedar por debajo del timeout del frontend (60 s).

## CORS

En `dev`, Angular corre en `http://localhost:4200` y el backend en `:8080` → o bien `@CrossOrigin`/configuración CORS para `localhost:4200`, o bien (recomendado) **proxy de Angular** (`proxy.conf.json` → `/api` → `localhost:8080`), que es lo que asume [08 — Frontend](08-frontend-angular.md). En Kubernetes el Ingress sirve frontend y API bajo el mismo host, así que no hay CORS.

## Extensión futura: streaming (SSE)

No se implementa en el prototipo, pero la API lo deja previsto: un `GET /api/copilot/chat/stream` con `text/event-stream` emitiría eventos `node` (progreso: "consultando tus pedidos…") y `token`. LangGraph4j lo soporta vía `graph.stream(...)` (`AsyncGenerator<NodeOutput<State>>`), por lo que no exige rediseño del grafo.
