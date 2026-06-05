<script setup>
import LegacyInputPanel from './LegacyInputPanel.vue';
import GeneratedFilesPanel from './GeneratedFilesPanel.vue';

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
  diagnostics: {
    type: Array,
    required: true,
  },
  navigationTarget: {
    type: Object,
    default: null,
  },
});

const activeLegacyFile = defineModel('activeLegacyFile', { type: String, required: true });
const activeFile = defineModel('activeFile', { type: String, required: true });
const kannelSource = defineModel('kannelSource', { type: String, required: true });
const smppboxSource = defineModel('smppboxSource', { type: String, required: true });

defineEmits([
  'load-kannel-sample',
  'clear-kannel',
  'import-kannel-file',
  'load-smppbox-sample',
  'clear-smppbox',
  'import-smppbox-file',
  'copy-active',
  'download-active',
  'download-bundle',
]);
</script>

<template>
  <div class="converter-workspace">
    <LegacyInputPanel
      v-model:active-legacy-file="activeLegacyFile"
      v-model:kannel-source="kannelSource"
      v-model:smppbox-source="smppboxSource"
      :diagnostics="diagnostics"
      :navigation-target="navigationTarget"
      @load-kannel-sample="$emit('load-kannel-sample')"
      @clear-kannel="$emit('clear-kannel')"
      @import-kannel-file="$emit('import-kannel-file', $event)"
      @load-smppbox-sample="$emit('load-smppbox-sample')"
      @clear-smppbox="$emit('clear-smppbox')"
      @import-smppbox-file="$emit('import-smppbox-file', $event)"
    />

    <GeneratedFilesPanel
      v-model:active-file="activeFile"
      :file-names="fileNames"
      :output-lines="outputLines"
      :summary="summary"
      @copy-active="$emit('copy-active')"
      @download-active="$emit('download-active')"
      @download-bundle="$emit('download-bundle')"
    />
  </div>
</template>
