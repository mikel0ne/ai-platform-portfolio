# LangChain4j + Spring Boot + Google AI Studio (Gemini)

Aplicación Spring Boot con integración de LangChain4j y Google AI Studio (Gemini) usando la capa gratuita.

## 🚀 Ejecución rápida

### 1. Obtener API key

Crea una API key gratuita en https://aistudio.google.com/app/apikey

### 2. Configurar la API key

Exporta la variable de entorno (PowerShell):

```powershell
$env:GEMINI_API_KEY = "tu-api-key-aqui"
```

O en bash:

```bash
export GEMINI_API_KEY="tu-api-key-aqui"
```

### 3. Compilar

```powershell
mvn clean package -DskipTests
```

### 4. Ejecutar

```powershell
mvn spring-boot:run
```

O ejecutar el JAR:

```powershell
java -jar target/langchain4j-springboot-1.0.0.jar
```

## 📍 Endpoints

- `GET /api/llm/health` → Verificar estado
- `GET /api/llm/hello` → Hola mundo
- `POST /api/llm/chat` → Enviar prompt

### Ejemplo curl

```bash
# Health check
curl http://localhost:8080/api/llm/health

# Hola mundo
curl http://localhost:8080/api/llm/hello

# Enviar prompt
curl -X POST http://localhost:8080/api/llm/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "¿Cuál es la capital de España?"}'
```

### Alternativa en PowerShell (para evitar problemas de encoding)

```powershell
# Health check
Invoke-WebRequest -Uri http://localhost:8080/api/llm/health

# Hola mundo
Invoke-WebRequest -Uri http://localhost:8080/api/llm/hello

# Enviar prompt
$body = @{
    prompt = "¿Cuál es la capital de España?"
} | ConvertTo-Json

Invoke-WebRequest -Uri http://localhost:8080/api/llm/chat `
  -Method POST `
  -ContentType "application/json; charset=utf-8" `
  -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
```

## 📋 Configuración

La API key se lee de la variable de entorno `GEMINI_API_KEY` (ver `application.yml`):

```yaml
langchain4j:
  gemini:
    api-key: ${GEMINI_API_KEY:}
    model-name: gemini-2.5-flash-lite
```

## ✅ Requisitos

- Java 17+
- Maven 3.6+
- Una API key de Google AI Studio (gratis con la capa free)
