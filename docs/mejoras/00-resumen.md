# Auditoría L1 Support Copilot — Documento de mejoras

Revisión completa del proyecto (agente LangGraph4j, capa Spring, tests, build, infra y
docs). Para cada punto se explica **qué es**, **por qué no está bien** y **cómo se
soluciona de forma sencilla**. No se entra al detalle línea a línea: es una guía de
decisión.

> Valoración global: el sistema está bien diseñado (reglas de negocio en Java, seguridad
> por repositorio, human-in-the-loop, routing determinista). Lo de abajo es **pulido de
> nivel experto**, no rescate.

> El detalle de implementación (clases implicadas, enfoque, tests y verificación por
> problema) está en el documento hermano [`01-playbook-implementacion.md`](01-playbook-implementacion.md).

## Progreso

- **✅ #1**: Password local → variable de entorno con default
- **✅ #2**: Eliminado el andamiaje `LlmController` + `LlmService` (no lo usaba nadie)
- **✅ #3**: Eliminada la reflexión `candidateLabel()`; los candidatos ya son `String`
- **✅ #6**: Aislamiento de tests con `@Transactional`, sin `finally` enmascarador
- **✅ #13**: CI + cobertura (`.github/workflows/ci.yml` + JaCoCo en `pom.xml`)
- **✅ #16**: `CacheController.clear()` oculto (`@Hidden`); orden de tags por springdoc (`tags-sorter=alpha`)

Pendientes: #4, #5, #7–#12, #14, #15, #17 (11 de 17)

---

## ⚡ Cambios de riesgo casi nulo (sin tocar código de producción)

Estos no modifican lógica Java/TypeScript en runtime, así que **no pueden provocar
regresiones funcionales**. Son los más seguros para empezar:

| # | Punto | Qué se toca | Riesgo |
|---|-------|-------------|:---:|
| 5 | Sincronizar documentación (modelos, diagramas) | Solo ficheros `.md` (`README.md`, `docs/`) | Nulo |
| 6 | Aislamiento de tests | Solo ficheros de test (`SupportAgentFlowTest.java`); cero código de producción | ~Nulo (tests) |
| 13 | Añadir CI + cobertura (JaCoCo) | Añade `.github/workflows/` y plugin en `pom.xml`; no toca runtime | ~Nulo (build/infra) |
| 1 | Password local a variable de entorno | Solo `application-local.yml` (perfil local); con default explícito el arranque es idéntico | ~Nulo (config) |

> **#5** es 100 % documentación (cero código). **#6** solo toca los propios tests, nunca
> código de producción — a lo sumo un test que antes pasaba "ocultando" su fallo dejará
> de pasar, que es exactamente lo que queremos. **#13** y **#1** son build/CI y config.
> El resto de puntos (#2, #3, #4, #7–#12, #14–#17) sí implican cambios de código de
> producción y conviene cubrirlos con tests.

---

## Cómo leer las prioridades

Cada punto lleva dos etiquetas:

- **Gravedad**: 🔴 alta · 🟠 media · 🟡 baja (riesgo de bug, seguridad o deuda real).
- **ROI** (esfuerzo→beneficio): ⭐⭐⭐ alto (poco esfuerzo, mucho beneficio) · ⭐⭐ medio · ⭐ bajo.

---

## Resumen ordenado por ROI (esfuerzo/beneficio)

| # | Punto | Gravedad | ROI | Esfuerzo | Estado |
|---|-------|:---:|:---:|:---:|:---:|
| 1 | Password hardcodeada en `application-local.yml` | 🟠 | ⭐⭐⭐ | Bajo | ✅ |
| 2 | Eliminar/sanear `LlmController`+`LlmService` (andamiaje) | 🟡 | ⭐⭐⭐ | Bajo | ✅ |
| 3 | Quitar `candidateLabel()` por reflexión (código legacy) | 🟡 | ⭐⭐⭐ | Bajo | ✅ |
| 4 | Centralizar reglas de intents (`isAction`/`needsVerify`) | 🟠 | ⭐⭐⭐ | Bajo | ⏳ |
| 5 | Sincronizar documentación (modelos, diagramas del grafo) | 🟡 | ⭐⭐⭐ | Bajo | ⏳ |
| 6 | Aislamiento de tests (BD compartida + `finally` que oculta fallos) | 🔴 | ⭐⭐ | Medio | ✅ |
| 7 | Estado del agente "stringly-typed" (clave única de verdad) | 🟠 | ⭐⭐ | Medio | ⏳ |
| 8 | Tarjetas de UI tipadas de extremo a extremo (no `Map`) | 🟠 | ⭐⭐ | Medio | ⏳ |
| 9 | Canal nodo→Responder por prefijos string mágicos | 🟠 | ⭐⭐ | Medio | ⏳ |
| 10 | Unificar nodos Cancel/Return duplicados | 🟡 | ⭐⭐ | Medio | ⏳ |
| 11 | Revisar prompts (indicaciones, no frases literales) | 🟠 | ⭐⭐ | Medio | ⏳ |
| 12 | Beans desambiguados por `@Qualifier("string")` | 🟡 | ⭐⭐ | Medio | ⏳ |
| 13 | Añadir CI + cobertura (JaCoCo) | 🟠 | ⭐⭐ | Medio-Alto | ✅ |
| 14 | Adelgazar `RouterNode` (hace demasiado) | 🟡 | ⭐ | Alto | ⏳ |
| 15 | Validación declarativa en entidades JPA | 🟡 | ⭐ | Bajo | ⏳ |
| 16 | Menores: auth en `CacheController`, orden de tags OpenAPI | 🟡 | ⭐ | Bajo | ✅ |
| 17 | Portabilidad `data.sql` (funciones H2 vs Postgres) | 🟡 | ⭐ | Bajo | ⏳ |

---

## Detalle por punto

### 1. Password hardcodeada en `application-local.yml` 🟠 ⭐⭐⭐ ✅

**Estado:** COMPLETO. La contraseña se lee de variable de entorno con default explícito
en `application-local.yml`.

- **Qué era:** la contraseña de Postgres local (`change-me`) estaba escrita en un fichero
  versionado.
- **Por qué no estaba bien:** quedaba para siempre en el historial de git; rotarla obligaba a un
  commit; cualquiera que clone la veía. Era mala práctica aunque fuera solo "local".
- **Cómo se solucionó:** se lee de variable de entorno con un default explícito
  (`${DB_PASSWORD:change-me}`), igual que ya se hace en el perfil prod.

### 2. Eliminar o sanear `LlmController` + `LlmService` 🟡 ⭐⭐⭐ ✅

**Estado:** COMPLETO. Borrados `LlmController` y `LlmService`; no los usaba nadie (ni el
grafo, ni el routing, ni el frontend).

- **Qué es:** endpoints `/llm/chat` y `/llm/hello` con DTOs definidos dentro del
  controlador y sin validación; el servicio es un passthrough trivial al modelo.
- **Por qué no está bien:** parece andamiaje de "hola mundo" que sobrevivió al prototipo;
  ensucia la API pública y un experto no lo dejaría ahí.
- **Cómo se soluciona:** si la demo no los usa, borrarlos; si se conservan, mover los DTOs
  a `dto/` y validar la entrada (`@NotBlank`, `@Size`).

### 3. Quitar `candidateLabel()` por reflexión 🟡 ⭐⭐⭐ ✅

**Estado:** COMPLETO. `candidates()` devuelve la lista tal cual; método de reflexión
borrado.

- **Qué es:** en `SupportState`, una función que usa reflexión para etiquetar candidatos
  de tipos heterogéneos.
- **Por qué no está bien:** el propio diseño ya guarda los candidatos como texto plano, así
  que esa rama casi nunca se ejecuta: es código muerto, frágil y lento.
- **Cómo se soluciona:** tratar los candidatos como `String` directamente y borrar la
  reflexión.

### 4. Centralizar las reglas de los intents 🟠 ⭐⭐⭐
- **Qué es:** la lógica de "qué intención es una acción" y "cuál necesita pasar por
  verify" está repartida entre `RouterNode` y `SupportRouting`, con listas hardcoded.
- **Por qué no está bien:** añadir un intent nuevo obliga a acordarse de tocar varios
  sitios; es fácil que se desincronicen y aparezca un bug sutil.
- **Cómo se soluciona:** que el propio enum `Intent` exponga `isAction()` y `needsVerify()`,
  y que el routing pregunte ahí. Una sola fuente de verdad.

### 5. Sincronizar la documentación 🟡 ⭐⭐⭐
- **Qué es:** el README cita modelos `gemini-2.5-*` cuando el código usa
  `gemini-3.1-flash-lite`; los diagramas del grafo difieren entre README, `docs/grafo.md`
  y el código.
- **Por qué no está bien:** la documentación es la fuente de verdad de las decisiones de
  diseño en este proyecto; si miente, deja de servir y confunde.
- **Cómo se soluciona:** actualizar nombres de modelo y dejar un único diagrama canónico
  del grafo referenciado desde el resto.

### 6. Aislamiento de tests 🔴 ⭐⭐ ✅

**Estado:** COMPLETO. `SupportAgentFlowTest` usa `@Transactional` con rollback automático;
todos los `finally` de restauración eliminados (commit `1956513`).

- **Qué era:** los tests de flujo compartían la misma BD H2, reseteaban estado en
  `@BeforeEach` y restauraban en bloques `finally`.
- **Por qué no estaba bien:** era lo más grave a nivel de calidad. Un `finally` que restaura
  estado puede **enmascarar un fallo** ocurrido en el `try`: el test "pasaba" tapando su
  propio error. Y el estado compartido hacía los tests dependientes del orden.
- **Cómo se solucionó:** cada test corre en transacción propia (aislamiento real). El grafo
  se invoca síncronamente en el hilo del test y todas las tools usan `@Transactional(REQUIRED)`,
  por lo que se unen a esa transacción y sus escrituras se revierten al rollback automático.
  Además, cada test usa `conversationId` propio → el checkpointer en memoria no filtra
  estado entre tests (orden de ejecución independiente garantizado).

### 7. Estado del agente "stringly-typed" 🟠 ⭐⭐
- **Qué es:** el estado solo declara formalmente una clave (`history`); el resto se leen y
  escriben por su nombre en texto, y además el servicio repite esos mismos literales al
  inicializar el turno.
- **Por qué no está bien:** no hay una única definición del "shape" del estado; un typo en
  una clave es un bug silencioso en runtime que el compilador no detecta.
- **Cómo se soluciona:** centralizar las claves (constantes) y/o un pequeño builder/factory
  del estado inicial reutilizado por el servicio y los tests.

### 8. Tarjetas de UI tipadas de extremo a extremo 🟠 ⭐⭐
- **Qué es:** los nodos construyen las tarjetas (pedidos, tickets, devoluciones) como
  mapas genéricos con claves de texto, y el servicio las vuelve a parsear con conversiones
  defensivas repetidas.
- **Por qué no está bien:** es frágil (claves a mano), repetitivo y poco profesional;
  además ya existen DTOs tipados para esas tarjetas.
- **Cómo se soluciona:** que el estado transporte directamente los records tipados que ya
  existen, eliminando el remapeo manual del servicio.

### 9. Canal nodo→Responder por prefijos string 🟠 ⭐⭐
- **Qué es:** algunos nodos escriben instrucciones para el redactor con prefijos de texto
  (p. ej. `"CLARIFY:"`) que el prompt del Responder reconoce por substring.
- **Por qué no está bien:** es un contrato implícito y frágil entre Java y el prompt: si el
  prefijo cambia en un lado y no en el otro, falla en silencio.
- **Cómo se soluciona:** modelar esa "directiva de redacción" como un tipo explícito (un
  enum/record en el estado) en lugar de acoplar por cadena de texto.

### 10. Unificar nodos Cancel/Return 🟡 ⭐⭐
- **Qué es:** `CancelCheckNode`/`ReturnCheckNode` y `CancelExecNode`/`ReturnExecNode` son
  casi idénticos.
- **Por qué no está bien:** duplicación: un cambio en la lógica hay que hacerlo dos veces y
  es fácil que diverjan.
- **Cómo se soluciona:** extraer una plantilla común parametrizada por la tool de
  comprobación, la de ejecución y los textos.

### 11. Revisar los prompts (indicaciones, no frases) 🟠 ⭐⭐
- **Qué es:** algunos prompts contienen reglas de negocio "encubiertas" como pistas al
  modelo (p. ej. preferencias sobre cuándo abrir incidencia).
- **Por qué no está bien:** la norma del proyecto es dar **indicaciones** de qué comunicar,
  no frases literales, y que los datos/estados los decida Java. Mezclarlo resta naturalidad
  y mantenibilidad.
- **Cómo se soluciona:** revisar `Responder`/`IntentClassifier`/`Verifier`, mover reglas de
  negocio a Java donde toque y dejar el prompt como redactor. Documentar los prompts (hoy
  no están en `docs/`).

### 12. Beans desambiguados por `@Qualifier("string")` 🟡 ⭐⭐
- **Qué es:** hay varios `ChatModel`/`Responder` (distinta temperatura) que se inyectan por
  nombre en texto.
- **Por qué no está bien:** un typo en el qualifier no lo detecta el compilador; se
  descubre en runtime.
- **Cómo se soluciona:** anotaciones `@Qualifier` propias y tipadas (p. ej.
  `@Deterministic`, `@ResponseModel`, `@ChatModel`).

### 13. Añadir CI + cobertura 🟠 ⭐⭐ ✅

**Estado:** COMPLETO. Workflow en `.github/workflows/ci.yml` y JaCoCo configurado en
`backend/pom.xml` con umbral mínimo de cobertura.

- **Qué era:** no había pipeline (`.github/workflows/`) ni medición de cobertura (JaCoCo).
- **Por qué no estaba bien:** los tests no se ejecutaban solos en cada cambio; nada impedía
  introducir una regresión sin enterarse.
- **Cómo se solucionó:** workflow mínimo de build+test en cada push/PR a todas las ramas;
  plugin de cobertura (JaCoCo) con reporte y umbral configurable (no bloqueante de momento,
  para permite evolución gradual). El workflow ejecuta `mvn -B test` desde el directorio
  `backend/` con JDK 17.

### 14. Adelgazar `RouterNode` 🟡 ⭐
- **Qué es:** concentra clasificación + extracción de foco + navegación + digresión en una
  clase muy grande.
- **Por qué no está bien:** clase con demasiadas responsabilidades, difícil de leer y
  testear de forma aislada.
- **Cómo se soluciona:** extraer colaboradores (resolución de foco, navegación) con sus
  propios tests. *Esfuerzo alto y algo de riesgo: dejar para el final.*

### 15. Validación declarativa en entidades JPA 🟡 ⭐
- **Qué es:** las entidades confían solo en `NOT NULL` de SQL, sin anotaciones
  (`@NotNull`/`@NotBlank`).
- **Por qué no está bien:** se puede construir una entidad inválida en Java y solo falla al
  guardar.
- **Cómo se soluciona:** añadir las anotaciones de validación donde aplique.

### 16. Menores de la capa web 🟡 ⭐ ✅

**Estado:** COMPLETO. `clear()` marcado `@Hidden`; `OpenApiConfig` borrado, orden de tags
delegado en springdoc (`tags-sorter=alpha`).

- **Qué es:** `CacheController.clear()` no tiene ninguna protección; `OpenApiConfig`
  reordena los tags a mano.
- **Por qué no está bien:** cualquiera puede vaciar la caché; el reordenado manual es
  código innecesario.
- **Cómo se soluciona:** restringir/ocultar el endpoint de caché y usar la opción de
  ordenación de springdoc en vez de código.

### 17. Portabilidad de `data.sql` 🟡 ⭐
- **Qué es:** el seed usa funciones específicas de H2 (p. ej. `DATEADD`).
- **Por qué no está bien:** puede no migrar limpio a Postgres en prod.
- **Cómo se soluciona:** usar SQL portable o seeds separados por dialecto.

---

## Vista alternativa: ordenado por gravedad

1. 🔴 **#6** Aislamiento de tests (puede ocultar fallos reales).
2. 🟠 **#1** Password hardcodeada (seguridad).
3. 🟠 **#4** Reglas de intents dispersas (riesgo de bug al evolucionar).
4. 🟠 **#7** Estado stringly-typed (bugs silenciosos por typo).
5. 🟠 **#8** Tarjetas no tipadas (fragilidad en el borde REST↔agente).
6. 🟠 **#9** Canal nodo→Responder por strings (acoplamiento frágil).
7. 🟠 **#11** Prompts con reglas de negocio (incumple norma del proyecto).
8. 🟠 **#13** Sin CI (regresiones no detectadas).
9. 🟡 **#2, #3, #5, #10, #12, #14, #15, #16, #17** Deuda y pulido (sin riesgo inmediato).

---

## Recomendación de arranque

Si se quiere empezar por lo más rentable: **#1 → #2 → #3 → #4 → #5** (quick wins de un
tirón) y luego abordar **#6, #7, #8** como bloque de calidad de mayor valor.
