# 07 — Secretos y configuración

Política de configuración del despliegue: **qué va en cada sitio y qué no puede tocar
el repo jamás.**

## Mapa de configuración

| Dato | Sensible | Dónde vive | Quién lo consume |
|---|---|---|---|
| `GEMINI_API_KEY` | 🔴 sí | Secret `gemini-api-key` en `l1-copilot` (creado a mano) | backend |
| Password de Postgres compartido | 🔴 sí | Secret `postgres-credentials` en `infra` (lo crea [`03-setup-postgres.sh`](../../infra/scripts/03-setup-postgres.sh)) + Secret `postgres-credentials` en `l1-copilot` (creado a mano) | postgres / backend |
| Perfil Spring, URL JDBC, opciones JVM | no | ConfigMap (lo crea el chart a partir de `configmap.data` en `values-l1-copilot-backend.yaml`) | backend |
| Esquema + seed SQL del proyecto | no | `backend/src/main/resources/schema-postgresql.sql` y `data-postgresql.sql` (en repo) | [`scripts/setup-l1-copilot-db.sh`](../../scripts/setup-l1-copilot-db.sh) |
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
   de Secret; los Secrets se crean siempre imperativamente (kubectl o
   `infra/scripts/create-secrets.ps1`). `infra/.gitignore` excluye ficheros `.env*`.
2. **Los secretos se crean imperativamente:**
   - Una línea de `kubectl create secret`, o
   - [`infra/scripts/create-secrets.ps1`](../../infra/scripts/create-secrets.ps1) que
     lee un `.env` git-ignorado y materializa el Secret (ver §3).
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

## Camino recomendado: `create-secrets.ps1` con un `.env`

Crea un fichero `.env` git-ignorado (p. ej. `secrets-l1-copilot.env`):

```env
GEMINI_API_KEY=AIza...
POSTGRES_USER=appuser
POSTGRES_PASSWORD=change-me
```

Y materializa los Secrets:

```powershell
.\infra\scripts\create-secrets.ps1 -Namespace l1-copilot -SecretName gemini-api-key      -EnvFile secrets-l1-copilot.env
.\infra\scripts\create-secrets.ps1 -Namespace l1-copilot -SecretName postgres-credentials -EnvFile secrets-l1-copilot.env
```

> El script es idempotente (usa `kubectl apply` con `--dry-run=client -o yaml`); se
> puede re-ejecutar tras rotar la key.

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
