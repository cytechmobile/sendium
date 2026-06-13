<script setup>
import { ref } from 'vue';
import LegacyConfigEditor from './LegacyConfigEditor.vue';

defineProps({
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
const kannelSource = defineModel('kannelSource', { type: String, required: true });
const smppboxSource = defineModel('smppboxSource', { type: String, required: true });

const emit = defineEmits([
  'load-kannel-sample',
  'clear-kannel',
  'import-kannel-file',
  'load-smppbox-sample',
  'clear-smppbox',
  'import-smppbox-file',
]);

const kannelFileInput = ref(null);
const smppboxFileInput = ref(null);

function openImportPicker() {
  if (activeLegacyFile.value === 'kannel.conf') {
    kannelFileInput.value?.click();
    return;
  }

  smppboxFileInput.value?.click();
}

function importSelectedFile(event, eventName) {
  const file = event.target.files?.[0];
  event.target.value = '';

  if (file) {
    emit(eventName, file);
  }
}
</script>

<template>
  <v-card class="workspace-card converter-panel" elevation="0">
    <div class="panel-heading compact-heading">
      <div>
        <p class="panel-kicker">Legacy configs</p>
        <h2>{{ activeLegacyFile }}</h2>
      </div>
      <div class="panel-actions">
        <template v-if="activeLegacyFile === 'kannel.conf'">
          <v-btn variant="text" color="secondary" @click="openImportPicker">Import file</v-btn>
          <v-btn variant="text" color="secondary" @click="$emit('load-kannel-sample')">Load sample</v-btn>
          <v-btn variant="text" color="warning" @click="$emit('clear-kannel')">Clear</v-btn>
        </template>
        <template v-else>
          <v-btn variant="text" color="secondary" @click="openImportPicker">Import file</v-btn>
          <v-btn variant="text" color="secondary" @click="$emit('load-smppbox-sample')">Load sample</v-btn>
          <v-btn variant="text" color="warning" @click="$emit('clear-smppbox')">Clear</v-btn>
        </template>
      </div>
    </div>

    <input
      ref="kannelFileInput"
      type="file"
      accept=".conf,text/plain,*/*"
      hidden
      @change="importSelectedFile($event, 'import-kannel-file')"
    />
    <input
      ref="smppboxFileInput"
      type="file"
      accept=".conf,text/plain,*/*"
      hidden
      @change="importSelectedFile($event, 'import-smppbox-file')"
    />

    <v-tabs v-model="activeLegacyFile" color="primary" density="comfortable" class="file-tabs">
      <v-tab value="kannel.conf">kannel.conf</v-tab>
      <v-tab value="smppbox.conf">smppbox.conf</v-tab>
    </v-tabs>

    <template v-if="activeLegacyFile === 'kannel.conf'">
      <LegacyConfigEditor
        v-model="kannelSource"
        source-name="kannel.conf"
        :diagnostics="diagnostics"
        :navigation-target="navigationTarget"
        :rows="28"
        placeholder="Paste kannel.conf here..."
      />
    </template>

    <template v-else>
      <p class="input-help">
        Optional. Use this only when the old deployment accepted SMPP client binds through smppbox/opensmppbox.
        Normal Kannel bearerbox/smsbox settings are not treated as Sendium SMPP server config.
      </p>
      <LegacyConfigEditor
        v-model="smppboxSource"
        source-name="smppbox.conf"
        :diagnostics="diagnostics"
        :navigation-target="navigationTarget"
        :rows="28"
        placeholder="Paste smppbox.conf or opensmppbox.conf here..."
      />
    </template>
  </v-card>
</template>
