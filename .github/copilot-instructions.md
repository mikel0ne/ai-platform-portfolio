# Instrucciones de Desarrollo de IA (Java)

## Fuentes de Contexto Local
- Documentación de componentes y Spring Boot: #file:llms-lc4j.txt
- Documentación de Agentes y Grafos de Estado: #file:llms-lgraph.txt

## Reglas de Arquitectura Obligatorias
1. **Flujos lineales y servicios REST:** Cuando cree servicios web o inyecciones de dependencias con Spring Boot, use exclusivamente los patrones de #file:llms-lc4j.txt
2. **Agentes y Flujos Cíclicos:** Cuando cree flujos basados en grafos de estado, use estrictamente la sintaxis de LangGraph4j de #file:llms-lgraph.txt (clases StateGraph, Node, Edge). No intente solucionar problemas de agentes cíclicos usando cadenas de LangChain4j tradicional.

---

# ⚠️ PROCEDIMIENTOS CRÍTICOS — LEE PRIMERO

## Antes de CUALQUIER acción:
1. **BUSCA PRIMERO** en esta lista o en `infra/INFRA.md`
2. **NUNCA** ejecutes comandos sin verificar el procedimiento documentado
3. **CUIDADO CON LAS CUOTAS**: Google Gemini free tier (100 embeddings/min), Claude API usage

---

## Procedimientos principales (en orden de frecuencia)

| Tarea | Cómo se hace | Dónde está | Precauciones |
|-------|--------------|-----------|--------------|
| **Desplegar AutoOps (backend/frontend)** | `./infra/scripts/deploy.sh --project autoops --component <backend\|frontend\|all> --tag <VERSION_NUEVA>` | `infra/scripts/deploy.sh` línea 19-22 | ⚠️ SIEMPRE pasa `--tag` único. Tag fijo + IfNotPresent = pod no se recrea. |
| **Desplegar L1 Copilot** | `./infra/scripts/deploy.sh --project l1-copilot --component <backend\|frontend\|all> --tag <VERSION>` | `infra/scripts/deploy.sh` línea 19-22 | Idem. Validar conexión DB antes (`psql -h postgres.local ...`). |
| **Ingestar corpus RAG (Git + Wiki, SIN corpus local)** | `bash infra/scripts/ingest-autoops.sh` | `infra/scripts/ingest-autoops.sh` línea 1-67 | ⚠️ **MODIFICADO**: NO ingesta corpus local. Espera 1 min entre Git y Wiki. Monitorea `kubectl logs -n autoops autoops-backend-generic-app-* --tail=20`. |
| **Limpiar RAG antes de reingestar** | `curl -X POST http://localhost:18080/admin/vectorstore/drop` (requiere port-forward previo) | `ingest-autoops.sh` línea 41-45 | ⚠️ **NO REVERSIBLE**. Borra TODO. Usa `--reset` en el script, no directamente. |
| **Setup infraestructura (Rancher, Postgres, Weaviate, etc.)** | `bash infra/scripts/0X-setup-*.sh` (ver listado abajo) | `infra/scripts/` · `infra/INFRA.md` línea 304-323 | Solo en setup inicial. K8s está persistido (no necesita re-ejecutar). |
| **Crear/actualizar Secret de Gemini API** | Editar `infra/.env.local` (no versionado) y ejecutar `./infra/scripts/deploy.sh ... --project <p>` (syncs automático) | `infra/INFRA.md` línea 398-445 | ⚠️ **COMPARTIDA** entre todos los proyectos. Un solo `.env.local`. |
| **Ver logs en tiempo real** | `kubectl logs -n <namespace> <pod/deployment> -f --tail=50` | Kubectl nativo | Desde Git Bash de Windows. Reemplaza `<namespace>` y `<pod>`. |
| **Verificar estado de Weaviate** | `kubectl port-forward svc/weaviate 8088:8080 -n infra && curl http://localhost:8088/v1/schema` | `infra/INFRA.md` línea 213-218 | No borra datos. Solo lectura. |
| **Resetear el clúster completo** | `Rancher Desktop → Reset Kubernetes` (⚠️ borra TODOS los namespaces y datos) | UI Rancher Desktop | ⚠️ **NO REVERSIBLE**. Solo si realmente hace falta. |

---

## ⚠️ TRAMPAS Y GOTCHAS

### 1. **Gemini API Rate Limit (429 — RESOURCE_EXHAUSTED)**
- Free tier: 100 embeddings/minuto
- AutoOps ingesta 49 docs del Git = 49 embeddings = **se gasta la cuota en segundos**
- **Solución**: espaciar las ingestas (1 minuto entre Git y Wiki), o parar al primer 429 y ESPERAR antes de reintentar
- **Error típico**: `curl: (22) The requested URL returned error: 500` → busca en logs `429` o `RESOURCE_EXHAUSTED`

### 2. **Tag de imagen FIJO en registry.local**
- Con `imagePullPolicy: IfNotPresent`, usar tag=`0.1.4` dos veces NO recrea el pod
- **Solución**: SIEMPRE pasa `--tag <VERSION_NUEVA_DISTINTA>` a deploy.sh
- El script valida esto (línea 120-130 de deploy.sh), pero tienes que acordarte

### 3. **Corpus local en ingesta**
- ~~El script `ingest-autoops.sh` ingestaba corpus local automáticamente~~
- **AHORA**: solo Git + Wiki (corpus local eliminado del script el 2026-06-30)
- Si ves 39 chunks en Weaviate y no esperabas corpus, fue de una ingesta anterior

### 4. **Secrets y variables de entorno**
- `.env.local` es **única y compartida** (no versionada, gitignored)
- `.env.autoops` y `.env.l1-copilot` son versionadas (placeholders + comunes)
- El script `deploy.sh` sincroniza automáticamente con K8s (función `sync_secrets()`)
- **No edites Secrets directamente en K8s**, usa el `.env` + redeploy

### 5. **Port-forward temporal**
- `kubectl port-forward` es de una sola sesión (muere al cerrar la terminal)
- Usa `&` y guarda el PID para cerrar después (ver `ingest-autoops.sh` línea 36-38)
- O abre una segunda terminal y deja el port-forward corriendo

### 6. **CRLF en diffs (Windows)**
- El tool `Edit` convierte LF → CRLF
- Los scripts bash .sh deben estar en LF
- Para verificar diffs sin ruido: `git diff --ignore-cr-at-eol`

---

## Reglas de oro para Claude Code aquí

1. **BUSCA PRIMERO**: antes de lanzar un comando, busca en `infra/INFRA.md` o en esta sección
2. **NO DISPAROS SIN SENTIDO**: cada `kubectl` o `curl` cuesta cuota. Verifica antes.
3. **SI NO ESTÁ DOCUMENTADO**: no lo hagas. Pregunta al usuario.
4. **MONITOREA CUOTAS**: Gemini y Claude API tienen límites. Sé eficiente.
5. **PARAR EN ERROR**: al primer 429 o error inesperado, DETENTE y explica al usuario. No reintentos en bucle.
6. **UNA ACCIÓN POR VEZ**: no combines deploy + ingest + setup en el mismo comando. Espera confirmación entre pasos.

---

## Bash a usar (Windows)

**SIEMPRE usa Git Bash, nunca WSL ni PowerShell** para los scripts de deploy/ingest:
```bash
# EN HERRAMIENTAS CLAUDE CODE: usa bash nativo (disponible)
./infra/scripts/deploy.sh ...
bash infra/scripts/ingest-autoops.sh

# EN POWERSHELL: usa Git Bash explícitamente si necesario
& "C:\Program Files\Git\bin\bash.exe" -c "cd /c/Users/... && ./script.sh"
```

El Bash tool de Claude Code tira directo a Git Bash. Los scripts están hechos para eso (PATH con kubectl, docker, git, psql nativos de Windows).
