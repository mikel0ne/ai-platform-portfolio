# 03 — Weaviate (base vectorial del RAG)

AutoOps usa **Weaviate** como almacén de embeddings y motor de búsqueda del RAG. Se elige sobre pgvector porque
ofrece **búsqueda híbrida (BM25 + vector)** y **reranking** de fábrica, que es justo lo que necesita el "RAG
avanzado" (ver `docs/arquitectura/02` ADR-005). Los **embeddings los calcula AutoOps** (LangChain4j), así que
Weaviate corre **sin módulo vectorizador** (`vectorizer: none`); el único módulo opcional es el reranker.

## 1. Weaviate (manifiesto)

`k8s/10-weaviate.yaml`:

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: weaviate-data
  namespace: autoops
spec:
  accessModes: ["ReadWriteOnce"]
  resources:
    requests:
      storage: 2Gi
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: weaviate
  namespace: autoops
spec:
  serviceName: weaviate
  replicas: 1
  selector:
    matchLabels:
      app: weaviate
  template:
    metadata:
      labels:
        app: weaviate
        app.kubernetes.io/part-of: autoops
    spec:
      containers:
        - name: weaviate
          image: cr.weaviate.io/semitechnologies/weaviate:1.27.0
          args: ["--host", "0.0.0.0", "--port", "8080", "--scheme", "http"]
          ports:
            - { containerPort: 8080, name: http }
            - { containerPort: 50051, name: grpc }
          env:
            - { name: PERSISTENCE_DATA_PATH, value: /var/lib/weaviate }
            - { name: AUTHENTICATION_ANONYMOUS_ENABLED, value: "true" }   # local: sin auth
            - { name: QUERY_DEFAULTS_LIMIT, value: "25" }
            - { name: DEFAULT_VECTORIZER_MODULE, value: "none" }          # AutoOps trae los vectores
            - { name: ENABLE_MODULES, value: "" }                         # "reranker-transformers" si se usa (§3)
            - { name: CLUSTER_HOSTNAME, value: "node1" }
          volumeMounts:
            - { name: data, mountPath: /var/lib/weaviate }
          resources:
            requests: { cpu: 250m, memory: 512Mi }
            limits:   { memory: 1Gi }
          readinessProbe:
            httpGet: { path: /v1/.well-known/ready, port: 8080 }
            initialDelaySeconds: 5
            periodSeconds: 10
          livenessProbe:
            httpGet: { path: /v1/.well-known/live, port: 8080 }
            initialDelaySeconds: 30
            periodSeconds: 30
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: weaviate-data
---
apiVersion: v1
kind: Service
metadata:
  name: weaviate
  namespace: autoops
spec:
  selector:
    app: weaviate
  ports:
    - { port: 8080,  targetPort: 8080,  name: http }
    - { port: 50051, targetPort: 50051, name: grpc }
```

```powershell
kubectl apply -f k8s/10-weaviate.yaml
kubectl rollout status statefulset/weaviate -n autoops
kubectl exec -it weaviate-0 -n autoops -- wget -qO- http://localhost:8080/v1/.well-known/ready; echo OK
```

## 2. La clase `DocChunk`

El esquema lo crea **AutoOps al arrancar** (bootstrap idempotente en `WeaviateConfig`), no se gestiona a mano.
Forma lógica (detalle en `docs/arquitectura/05` §4):

```jsonc
{
  "class": "DocChunk",
  "vectorizer": "none",
  "properties": [
    { "name": "texto",   "dataType": ["text"] },
    { "name": "fuente",  "dataType": ["text"], "tokenization": "field" },
    { "name": "seccion", "dataType": ["text"] },
    { "name": "orden",   "dataType": ["int"] }
  ]
}
```

La **dimensión del vector** la determina el modelo de embeddings (Gemini `text-embedding-004` → 768). Si se
cambia de modelo, recrear la clase y **re-ingestar** (`docs/arquitectura/06` §4).

## 3. Reranking (opcional pero recomendado)

El reranking es la mayor ganancia de precisión del RAG. Weaviate lo provee con el módulo
`reranker-transformers`, que corre un cross-encoder en un servicio aparte:

```yaml
# k8s/11-weaviate-reranker.yaml (opcional)
apiVersion: apps/v1
kind: Deployment
metadata: { name: weaviate-reranker, namespace: autoops }
spec:
  replicas: 1
  selector: { matchLabels: { app: weaviate-reranker } }
  template:
    metadata: { labels: { app: weaviate-reranker, app.kubernetes.io/part-of: autoops } }
    spec:
      containers:
        - name: reranker
          image: cr.weaviate.io/semitechnologies/reranker-transformers:cross-encoder-ms-marco-MiniLM-L-6-v2
          ports: [ { containerPort: 8080 } ]
          resources:
            requests: { cpu: 250m, memory: 512Mi }
            limits:   { memory: 1Gi }
---
apiVersion: v1
kind: Service
metadata: { name: weaviate-reranker, namespace: autoops }
spec:
  selector: { app: weaviate-reranker }
  ports: [ { port: 8080, targetPort: 8080 } ]
```

Para activarlo, en el StatefulSet de Weaviate:

```yaml
            - { name: ENABLE_MODULES, value: "reranker-transformers" }
            - { name: RERANKER_INFERENCE_API, value: "http://weaviate-reranker.autoops.svc.cluster.local:8080" }
```

> **Si se omite el reranker:** AutoOps funciona igualmente con **búsqueda híbrida** (vector + BM25); el nodo
> `grade` del bucle CRAG califica sobre el score híbrido. El reranker mejora la precisión, no es imprescindible
> para el MVP. Por eso el manifiesto base de §1 lo deja desactivado.

## 4. Conexión desde AutoOps

La app recibe la conexión por variables de entorno (en su Deployment, guía 05):

```
AUTOOPS_WEAVIATE_HOST=weaviate.autoops.svc.cluster.local
AUTOOPS_WEAVIATE_PORT=8080
AUTOOPS_WEAVIATE_GRPC_PORT=50051
```

## 5. Acceso local (debug) y reset

```powershell
# Inspeccionar el esquema o las colecciones desde la máquina local
kubectl port-forward svc/weaviate 8088:8080 -n autoops
# http://localhost:8088/v1/schema   ·   http://localhost:8088/v1/objects

# Reset completo de datos (¡destruye el índice; hay que re-ingestar!)
kubectl delete statefulset weaviate -n autoops
kubectl delete pvc weaviate-data -n autoops
kubectl apply -f k8s/10-weaviate.yaml
```

La **ingesta del corpus** (poblar `DocChunk`) se ejecuta tras desplegar la app: ver guía
[07-operacion-troubleshooting.md](07-operacion-troubleshooting.md) §3.

Continuar con [04-dockerizacion.md](04-dockerizacion.md).
