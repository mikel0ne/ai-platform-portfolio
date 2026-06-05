# Instrucciones de Desarrollo de IA (Java)

## Fuentes de Contexto Local
- Documentación de componentes y Spring Boot: #file:llms-lc4j.txt
- Documentación de Agentes y Grafos de Estado: #file:llms-lgraph.txt

## Reglas de Arquitectura Obligatorias
1. **Flujos lineales y servicios REST:** Cuando cree servicios web o inyecciones de dependencias con Spring Boot, use exclusivamente los patrones de #file:llms-lc4j.txt
2. **Agentes y Flujos Cíclicos:** Cuando cree flujos basados en grafos de estado, use estrictamente la sintaxis de LangGraph4j de #file:llms-lgraph.txt (clases StateGraph, Node, Edge). No intente solucionar problemas de agentes cíclicos usando cadenas de LangChain4j tradicional.
