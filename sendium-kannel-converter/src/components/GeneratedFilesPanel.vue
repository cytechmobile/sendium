<script setup>
import { mdiContentCopy, mdiDownload, mdiDownloadBoxOutline } from '@mdi/js';
import SummaryMetrics from './SummaryMetrics.vue';

defineProps({
  fileNames: {
    type: Array,
    required: true,
  },
  outputLines: {
    type: Array,
    required: true,
  },
  summary: {
    type: Object,
    required: true,
  },
});

const activeFile = defineModel('activeFile', { type: String, required: true });

defineEmits(['copy-active', 'download-active', 'download-bundle']);
</script>

<template>
  <v-card class="workspace-card generated-card" elevation="0">
    <div class="panel-heading output-heading">
      <div>
        <p class="panel-kicker">Generated Sendium files</p>
        <h2>{{ activeFile }}</h2>
      </div>
      <div class="output-actions">
        <v-btn variant="tonal" color="secondary" :prepend-icon="mdiContentCopy" @click="$emit('copy-active')">
          Copy
        </v-btn>
        <v-btn variant="tonal" color="primary" :prepend-icon="mdiDownload" @click="$emit('download-active')">
          Download
        </v-btn>
        <v-btn variant="text" color="secondary" :prepend-icon="mdiDownloadBoxOutline" @click="$emit('download-bundle')">
          Bundle
        </v-btn>
      </div>
    </div>

    <SummaryMetrics :summary="summary" />

    <v-tabs v-model="activeFile" color="primary" density="comfortable" class="file-tabs">
      <v-tab v-for="fileName in fileNames" :key="fileName" :value="fileName" :aria-label="`Generated file ${fileName}`">
        {{ fileName }}
      </v-tab>
    </v-tabs>

    <div class="output-code" aria-label="Generated Sendium file preview">
      <div v-for="line in outputLines" :key="line.number" class="output-line">
        <span class="line-number">{{ line.number }}</span>
        <code>{{ line.content || ' ' }}</code>
      </div>
    </div>
  </v-card>
</template>
