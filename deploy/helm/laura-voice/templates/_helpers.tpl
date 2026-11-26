{{- define "lv.name" -}}{{ .Release.Name }}{{- end }}

{{- define "lv.labels" -}}
app.kubernetes.io/part-of: laura-voice
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end }}

{{- define "lv.selector" -}}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{- define "lv.image" -}}
{{- $reg := .root.Values.imageRegistry -}}
{{- if and $reg (not (contains "/" .image.repository)) -}}
{{ $reg }}/{{ .image.repository }}:{{ .image.tag }}
{{- else -}}
{{ .image.repository }}:{{ .image.tag }}
{{- end -}}
{{- end }}

{{- define "lv.placement" -}}
{{- with .Values.imagePullSecrets }}
imagePullSecrets: {{ toYaml . | nindent 2 }}
{{- end }}
{{- with .Values.nodeSelector }}
nodeSelector: {{ toYaml . | nindent 2 }}
{{- end }}
tolerations:
{{- with .Values.tolerations }}
{{ toYaml . }}
{{- end }}
{{- if .Values.tolerateNodeAway }}
- { key: node.kubernetes.io/unreachable, operator: Exists, effect: NoExecute }
- { key: node.kubernetes.io/not-ready, operator: Exists, effect: NoExecute }
{{- end }}
{{- if .Values.runtimeClassName }}
runtimeClassName: {{ .Values.runtimeClassName }}
{{- end }}
{{- end }}

{{- define "lv.tlsSecret" -}}
{{- .Values.tls.existingSecret | default (printf "%s-gateway-tls" .Release.Name) -}}
{{- end }}

{{- define "lv.resources" -}}
{{- $limits := deepCopy (.res.limits | default dict) -}}
{{- if .gpu }}{{- $_ := set $limits .root.Values.gpuResource 1 -}}{{- end -}}
requests: {{- toYaml (.res.requests | default dict) | nindent 2 }}
limits: {{- toYaml $limits | nindent 2 }}
{{- end }}
