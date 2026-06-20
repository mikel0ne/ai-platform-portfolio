# Grafo del agente de soporte (LangGraph4j)

Diagrama de la topología del grafo (`SupportAgentGraph.build()`). Cada turno HTTP
recorre el grafo de `START` a `END`; el ciclo conversacional se materializa entre
turnos gracias al checkpointer.

```mermaid
flowchart TD
    START([START]) --> router{"router<br/>clasifica intención"}

    %% Router -> destinos (conditional: fromRouter)
    router -->|"CANCEL/SUPPORT frescos"| verify{"verify<br/>resuelve pedido"}
    router --> tracking
    router --> cancel_check
    router --> return_check
    router --> support
    router --> tickets
    router --> faq
    router --> chatty
    router --> fallback
    router --> resolve_order

    %% Verify -> destinos (conditional: fromVerify)
    verify --> tracking
    verify --> cancel_check
    verify --> return_check
    verify --> resolve_order
    verify --> support
    verify --> clarify
    verify --> fallback

    %% Gates con ramificación
    resolve_order{"resolve_order"} -->|"tracking"| tracking
    resolve_order -->|"cancel"| cancel_check
    resolve_order -->|"return"| return_check
    resolve_order -->|"ask_user"| respond

    cancel_check{"cancel_check<br/>¿cancelable?"} -->|"ok"| cancel_exec
    cancel_check -->|"ask_user / no"| respond

    return_check{"return_check<br/>¿devolvible?"} -->|"ok"| return_exec
    return_check -->|"ask_user / no"| respond

    %% Terminales que ejecutan o aportan datos/instrucción
    tracking --> respond
    cancel_exec --> respond
    return_exec --> respond
    support --> respond
    tickets --> respond
    faq --> respond
    chatty --> respond
    fallback --> respond
    clarify --> respond

    %% Embudo final
    respond["respond<br/>redactor único:<br/>responder / chattyResponder"] --> FIN([END])

    classDef decision fill:#fde68a,stroke:#b45309,color:#000;
    classDef gate fill:#bfdbfe,stroke:#1d4ed8,color:#000;
    classDef exec fill:#bbf7d0,stroke:#15803d,color:#000;
    classDef redactor fill:#e9d5ff,stroke:#7c3aed,color:#000;
    class router,verify decision;
    class resolve_order,cancel_check,return_check gate;
    class cancel_exec,return_exec exec;
    class respond redactor;
```

## Leyenda

- 🟡 **Decisión** (`router`, `verify`): no redactan; deciden el siguiente nodo
  mediante *conditional edges* (`SupportRouting`).
- 🔵 **Gates** (`resolve_order`, `cancel_check`, `return_check`): comprueban reglas
  de negocio y ramifican (ejecutar o preguntar al usuario → `respond`).
- 🟢 **Exec** (`cancel_exec`, `return_exec`): ejecutan la acción real.
- 🟣 **respond**: redactor único (embudo final). Elige `responder` o
  `chattyResponder` según la intención.
