# Instrucciones de Desarrollo de IA (Java)

## Fuentes de Contexto Local
- Documentación de componentes y Spring Boot: #file:llms-lc4j.txt
- Documentación de Agentes y Grafos de Estado: #file:llms-lgraph.txt

## Reglas de Arquitectura Obligatorias
1. **Flujos lineales y servicios REST:** Cuando cree servicios web o inyecciones de dependencias con Spring Boot, use exclusivamente los patrones de #file:llms-lc4j.txt
2. **Agentes y Flujos Cíclicos:** Cuando cree flujos basados en grafos de estado, use estrictamente la sintaxis de LangGraph4j de #file:llms-lgraph.txt (clases StateGraph, Node, Edge). No intente solucionar problemas de agentes cíclicos usando cadenas de LangChain4j tradicional.

## Redacción de Prompts Orientados al Usuario
Cuando edites los prompts que redactan texto para el usuario final (sobre todo `Responder`, y cualquier otro `AiService` que hable de cara al cliente), escribe SIEMPRE **indicaciones** de qué debe comunicar el modelo, NUNCA la respuesta literal:

1. **Da la indicación, no la frase.** No escribas frases pensadas para que el modelo las suelte tal cual ("di esto", "responde exactamente…"). Describe el objetivo comunicativo y deja que el modelo elija las palabras. Ejemplo: en vez de *"dile que ya no se puede cancelar"*, escribe *"si el usuario quiere cancelar, explícale que ese procedimiento ya no es posible y reconduce hacia la alternativa que sí aplica"*. Esto mantiene un tono natural y evita respuestas robóticas o repetitivas.
2. **Cruza conceptos solo en la dirección que plantea el usuario.** No metas en la respuesta temas que el usuario no ha sacado. Si pide una devolución, no le hables de cancelación (y viceversa) salvo que él lo mencione. Anticipar un "ya no se puede X" que nadie ha pedido solo confunde.
3. **Las reglas de negocio las decide Java; el prompt solo redacta.** Estados, cifras, plazos y la viabilidad de cada gestión salen de `TOOL_RESULT` / los nodos del grafo, no de que el prompt las invente.

## Autoría y Justificación de Decisiones
Todo el código y la documentación de este proyecto han sido generados por ti (la IA) a lo largo de múltiples conversaciones, siguiendo las indicaciones del usuario. El usuario da las instrucciones, pero el resultado lo produces tú. Por tanto:

1. **Asume todo el trabajo como propio.** NO respondas con excusas del tipo "esto ya estaba así", "yo no he hecho esto" o "esto venía de antes". Aunque no se haya generado en la conversación actual, lo hiciste tú en otra. Trata todo el código y la documentación como obra tuya.
2. **Explica siempre el porqué de las decisiones de diseño.** Cuando se pregunte por qué algo está hecho de una manera, da la justificación de diseño, no una evasiva sobre la autoría.
3. **Si no recuerdas el razonamiento concreto** (porque se decidió en otra conversación), NO lo uses como excusa para no responder. En su lugar: revisa la documentación (`docs/`, especialmente `docs/arquitectura/`) y el propio código, que deben estar actualizados, y reconstruye la justificación a partir de ahí. Puedes señalar que el detalle exacto se decidió en otra sesión, pero acompáñalo SIEMPRE de la explicación deducida del código y la documentación ("esto está así por X, según se ve en…").
4. **Mantén la documentación actualizada** como fuente de verdad de las decisiones de diseño, para poder justificarlas en el futuro.

