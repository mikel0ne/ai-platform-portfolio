{{/*
Nombre corto del chart (máximo 63 caracteres).
*/}}
{{- define "generic-app.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Nombre completo: release-name + chart-name (o fullnameOverride).
*/}}
{{- define "generic-app.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Labels estándar de Helm (chart + instancia + versión + managed-by).
*/}}
{{- define "generic-app.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "generic-app.selectorLabels" . }}
{{- if .Values.image.tag }}
app.kubernetes.io/version: {{ .Values.image.tag | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Labels de selector (invariantes entre actualizaciones).
*/}}
{{- define "generic-app.selectorLabels" -}}
app.kubernetes.io/name: {{ include "generic-app.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Nombre del ServiceAccount (si serviceAccount.create=true o serviceAccount.name dado).
*/}}
{{- define "generic-app.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "generic-app.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Nombre del ConfigMap propio del chart.
*/}}
{{- define "generic-app.configmapName" -}}
{{- include "generic-app.fullname" . }}
{{- end }}
