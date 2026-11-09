# 07 — Secretos y configuración

Política de configuración del despliegue: **qué va en cada sitio y qué no puede tocar
el repo jamás.**

## Mapa de configuración

| Dato | Sensible | Dónde vive | Quién lo consume |
|---|---|---|---|
| `GEMINI_API_KEY` | 🔴 sí | Secret `gemini-api-key` en `l1-copilot` (lo sincroniza `deploy.sh` desde `infra/.env.local`) | backend |
| Password de Postgres compartido | 🔴 sí | Secret `postgres-credentials` en `infra` (lo crea [`03-setup-postgres.sh`](../../infra/scripts/03-setup-postgres.sh)) + Secret `postgres-credentials` en `l1-copilot` (lo sincroniza `deploy.sh`) | postgres / backend |
| Perfil Spring, URL JDBC, opciones JVM | no | ConfigMap (lo crea el chart a partir de `configmap.data` en `values-l1-copilot-backend.yaml`) | backend |
| Esquema + seed SQL del proyecto | no | `l1-copilot/backend/src/main/resources/schema-postgresql.sql` y `data-postgresql.sql` (en repo) | [`l1-copilot/scripts/setup-l1-copilot-db.sh`](../../scripts/setup-l1-copilot-db.sh) |
| Values del chart (recursos, host, probes…) | no | `infra/values-l1-copilot-*.yaml` (en repo) | helm install/upgrade |
| Modelo Gemini, TTL caché… | no | `application*.yml` dentro de la imagen | backend |

Criterio: **si rotarlo duele, es Secret; si se puede leer en un PR sin problema, es
values/ConfigMap/yml.**

> **Secrets entre namespaces:** los Secrets son por-namespace. El script
> `03-setup-postgres.sh` guarda el password en el namespace `infra`; el backend lo
> necesita en `l1-copilot`, así que se materializa ahí (`postgres-credentials`) con
> el mismo valor. Si en algún momento desincronizan: regenerar el de la app
> (regla 4 aplicada a este Secret).

## Reglas

1. **Ningún Secret con valores reales en git.** El repo no contiene plantillas YAML
   de Secret; la `GEMINI_API_KEY` real vive solo en `infra/.env.local` (git-ignored,
   compartida por todos los proyectos del portfolio).
   `infra/.gitignore` excluye ficheros `.env*` (salvo los `.example` y los de placeholders).
2. **Los secretos se materializan desde el `.env`:**
   - `deploy.sh` los sincroniza en cada despliegue (camino recomendado, ver arriba), o
   - [`infra/scripts/create-secrets.ps1`](../../infra/scripts/create-secrets.ps1) / una línea
     de `kubectl create secret` para casos puntuales.
   - En un entorno serio: External Secrets / Sealed Secrets / Vault.
   **Nunca** en `infra/values-*.yaml`: esos values están versionados.
3. **El backend recibe secretos como variables de entorno** vía `secretEnv` en el
   values del chart, que se traduce a `secretKeyRef` en el Deployment — nunca
   argumentos de comando (visibles en `kubectl describe pod`) ni ficheros commiteados.
4. **Rotación de la API key de Gemini:**
   ```bash
   kubectl create secret generic gemini-api-key -n l1-copilot \
     --from-literal=GEMINI_API_KEY=<nueva> --dry-run=client -o yaml | kubectl apply -f -
   kubectl rollout restart deploy/l1-copilot-backend -n l1-copilot   # los env no se refrescan en caliente
   ```
5. **Comprobación rápida de que un Secret existe y tiene las keys correctas** (sin
   imprimir valores):
   ```bash
   kubectl get secret gemini-api-key -n l1-copilot -o jsonpath="{.data}" | jq 'keys'
   ```

## Camino recomendado: `infra/.env.local` sincronizado por `deploy.sh`

Fuente de verdad única de los Secrets (centralizada, cross-project — ver
[`infra/INFRA.md`](../../infra/INFRA.md)):

- `infra/.env.local` — `GEMINI_API_KEY` y `POSTGRES_USER`/`POSTGRES_PASSWORD` **reales**,
  git-ignored, **compartidos por todos los proyectos del portfolio** (plantilla:
  `infra/.env.local.example`).
- `infra/.env.l1-copilot.local` — override puntual de ESTE proyecto (git-ignored,
  opcional; plantilla: `infra/.env.local.example`), p.ej. rotar Postgres
  solo en tu entorno. Normalmente no hace falta crearlo.

```env
# infra/.env.local  (git-ignored, compartido por todos los proyectos)
GEMINI_API_KEY=AIza...
```

Qué clave va a qué Secret lo declara `infra/projects/l1-copilot.env` (`SECRETS_BACKEND`):
`gemini-api-key` ← `GEMINI_API_KEY`; `postgres-credentials` ← `POSTGRES_USER`
`POSTGRES_PASSWORD`. **`deploy.sh` materializa los Secrets automáticamente** (`sync_secrets`)
en cada despliegue; no hay que crearlos a mano.

> Alternativa puntual (sin desplegar): `infra/scripts/create-secrets.ps1 -Namespace
> l1-copilot -SecretName <secret> -EnvFile <fichero.env>`, idempotente (usa `kubectl apply`
> con `--dry-run=client -o yaml`).

## Por qué la URL JDBC no es secreta

`jdbc:postgresql://postgres.infra.svc.cluster.local:5432/l1_copilot` solo tiene sentido
dentro del clúster (DNS interno) y no contiene credenciales (van por variables
separadas, que es como las espera Spring: `SPRING_DATASOURCE_USERNAME`/`PASSWORD`
tienen precedencia sobre el yml). Mantenerla en ConfigMap permite cambiar de BD sin
reconstruir la imagen.

## Checklist antes de cada commit en esta zona

- [ ] `git diff --staged` no contiene `apiKey`, `password`, `AIza` (prefijo de keys
      de Google) ni base64 sospechoso.
- [ ] Ningún `.env` ni `*-secret*.yaml` con valores reales en el diff.
- [ ] Ningún `values-*.yaml` referencia un Secret nuevo que no esté documentado en
      esta página.
