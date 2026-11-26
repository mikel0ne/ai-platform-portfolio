# Capturas y GIFs del README

Scripts para regenerar las imágenes de `docs/img/` contra el clúster desplegado (L1 en
`http://l1-copilot.local`, AutoOps en `http://autoops.local`).

Requisitos: Node con `playwright-core` (`npm install playwright-core` en una carpeta temporal, usa el
Chrome instalado) y Python con Pillow.

```powershell
# un escenario por ejecución: tracking | cancel | jailbreak (usuario Ana García, pedidos #456, #123, #789)
node l1-shots.mjs <carpeta-capturas> cancel
# espera N ms a que AutoOps termine la búsqueda (con Gemini 3.1 tarda 60-90 s)
node autoops-shots.mjs <carpeta-capturas> 90000
python build-gifs.py <carpeta-capturas> docs/img
```

Notas:

- Cada turno de L1 hace unas 3 llamadas al LLM y una búsqueda de AutoOps unas 5: hace falta cuota. Con
  Gemma 4 hay 503 y el límite de 16.000 tokens/min lo agota un turno; con `gemini-3.1-flash-lite`
  salió a la primera. Cambiar el modelo con el ConfigMap y reiniciar el pod, y devolverlo después.
- `l1-shots.mjs` sale con código 2 si el chat responde "Ahora mismo no puedo atenderte", para no guardar
  una captura de un error. El script de cancelar no pulsa el botón de confirmar: no modifica datos.
- Las capturas de Langfuse se hacen a mano en el panel del navegador (hace falta sesión) y se guardan
  como `langfuse-autoops-trace.jpg` en la carpeta de capturas.
