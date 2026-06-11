# 07 — Secretos y configuración

Política de configuración del despliegue: **qué va en cada sitio y qué no puede tocar el repo jamás.**

## Mapa de configuración

| Dato | Sensible | Dónde vive | Quién lo consume |
|---|---|---|---|
| `GEMINI_API_KEY` | 🔴 sí | Secret `gemini-api-key` (creado a mano) | backend |
| Usuario/password/DB de Postgres | 🔴 sí | Secret `postgres-credentials` (creado a mano) | postgres y backend |
| Perfil Spring, URL JDBC, opciones JVM | no | ConfigMap `backend-config` (en repo) | backend |
| Esquema + seed SQL | no | ConfigMap `postgres-init` (en repo) | postgres (initdb) |
| Modelo Gemini, TTL caché… | no | `application*.yml` dentro de la imagen | backend |

Criterio: **si rotarlo duele, es Secret; si se puede leer en un PR sin problema, es ConfigMap o yml.**

## Reglas

1. **Ningún Secret con valores reales en git.** En el repo solo viven plantillas `*.example.yaml` con placeholders. `.gitignore` debe excluir `k8s/*-secret.yaml` y similares por si alguien materializa una plantilla.
2. **Los secretos se crean imperativamente** (una línea de `kubectl create secret`, documentada en las guías 03 y 04) o, en un entorno serio, con External Secrets/Sealed Secrets/Vault.
3. **El backend recibe secretos como variables de entorno** vía `secretKeyRef` — nunca argumentos de comando (visibles en `kubectl describe pod`) ni ficheros commiteados.
4. **Rotación de la API key de Gemini:**
   ```powershell
   kubectl create secret generic gemini-api-key -n l1-copilot `
     --from-literal=GEMINI_API_KEY=<nueva> --dry-run=client -o yaml | kubectl apply -f -
   kubectl rollout restart deploy/backend -n l1-copilot   # los env no se refrescan en caliente
   ```
5. **Comprobación rápida de que un Secret existe y tiene las keys correctas** (sin imprimir valores):
   ```powershell
   kubectl get secret gemini-api-key -n l1-copilot -o jsonpath="{.data}" | ConvertFrom-Json | Get-Member -MemberType NoteProperty
   ```

## Plantillas incluidas en el repo

- [`k8s/gemini-secret.example.yaml`](../../k8s/gemini-secret.example.yaml)
- [`k8s/postgres-credentials.example.yaml`](../../k8s/postgres-credentials.example.yaml)

Uso: copiarlas **fuera del repo** (o renombrarlas a un nombre git-ignorado), rellenar, `kubectl apply -f`, borrar la copia.

## Por qué la URL JDBC no es secreta

`jdbc:postgresql://postgres:5432/copilot` solo tiene sentido dentro del clúster (DNS interno) y no contiene credenciales (van por variables separadas, que es como las espera Spring: `SPRING_DATASOURCE_USERNAME`/`PASSWORD` tienen precedencia sobre el yml). Mantenerla en ConfigMap permite cambiar de BD sin reconstruir la imagen.

## Checklist antes de cada commit en esta zona

- [ ] `git diff --staged` no contiene `apiKey`, `password`, `AIza` (prefijo de keys de Google) ni base64 sospechoso.
- [ ] Los `*.example.yaml` siguen teniendo placeholders, no valores.
- [ ] Ningún manifiesto nuevo monta un Secret que no esté documentado en esta página.
