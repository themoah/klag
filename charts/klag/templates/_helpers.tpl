{{/*
Expand the name of the chart.
*/}}
{{- define "klag.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "klag.fullname" -}}
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
Create chart name and version as used by the chart label.
*/}}
{{- define "klag.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "klag.labels" -}}
helm.sh/chart: {{ include "klag.chart" . }}
{{ include "klag.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "klag.selectorLabels" -}}
app.kubernetes.io/name: {{ include "klag.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "klag.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "klag.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Create the name of the secret for Kafka credentials
*/}}
{{- define "klag.kafkaSecretName" -}}
{{- if .Values.kafka.existingSecret }}
{{- .Values.kafka.existingSecret }}
{{- else }}
{{- include "klag.fullname" . }}-kafka
{{- end }}
{{- end }}

{{/*
Create the name of the secret for OTLP credentials
*/}}
{{- define "klag.otlpSecretName" -}}
{{- if .Values.metrics.otlp.existingSecret }}
{{- .Values.metrics.otlp.existingSecret }}
{{- else }}
{{- include "klag.fullname" . }}-otlp
{{- end }}
{{- end }}

{{/*
Create the name of the secret for Datadog credentials
*/}}
{{- define "klag.datadogSecretName" -}}
{{- if .Values.metrics.datadog.existingSecret }}
{{- .Values.metrics.datadog.existingSecret }}
{{- else }}
{{- include "klag.fullname" . }}-datadog
{{- end }}
{{- end }}

{{/*
Determine if Kafka secret should be created
*/}}
{{- define "klag.createKafkaSecret" -}}
{{- if and .Values.kafka.saslJaasConfig (not .Values.kafka.existingSecret) }}
{{- true }}
{{- end }}
{{- end }}

{{/*
Determine if OTLP secret should be created
*/}}
{{- define "klag.createOtlpSecret" -}}
{{- if and (eq .Values.metrics.reporter "otlp") .Values.metrics.otlp.headers (not .Values.metrics.otlp.existingSecret) }}
{{- true }}
{{- end }}
{{- end }}

{{/*
Determine if Datadog secret should be created
*/}}
{{- define "klag.createDatadogSecret" -}}
{{- if and (eq .Values.metrics.reporter "datadog") (or .Values.metrics.datadog.apiKey .Values.metrics.datadog.appKey) (not .Values.metrics.datadog.existingSecret) }}
{{- true }}
{{- end }}
{{- end }}
