<script setup>
import { mdiAlertOctagonOutline, mdiAlertOutline, mdiInformationOutline } from '@mdi/js';

defineProps({
  diagnostics: {
    type: Array,
    required: true,
  },
  summary: {
    type: Object,
    required: true,
  },
});

const emit = defineEmits(['navigate-diagnostic']);

const diagnosticIcon = {
  error: mdiAlertOctagonOutline,
  warning: mdiAlertOutline,
  info: mdiInformationOutline,
};

function diagnosticClass(diagnostic) {
  return `diagnostic-item diagnostic-${diagnostic.severity}`;
}

function canNavigateDiagnostic(diagnostic) {
  return Boolean(diagnostic.source && diagnostic.line);
}

function navigateDiagnostic(diagnostic) {
  if (canNavigateDiagnostic(diagnostic)) {
    emit('navigate-diagnostic', diagnostic);
  }
}
</script>

<template>
  <v-card class="diagnostics-card" elevation="0">
    <div class="panel-heading compact">
      <div>
        <p class="panel-kicker">Migration review</p>
        <h2>Warnings and manual steps</h2>
      </div>
      <v-chip :color="summary.errors ? 'error' : 'success'" variant="tonal">
        {{ diagnostics.length }} items
      </v-chip>
    </div>

    <v-list v-if="diagnostics.length" class="diagnostic-list" lines="three">
      <v-list-item
        v-for="(diagnostic, index) in diagnostics"
        :key="`${diagnostic.source || 'global'}-${diagnostic.line || 'global'}-${index}`"
        :class="[diagnosticClass(diagnostic), { 'diagnostic-navigable': canNavigateDiagnostic(diagnostic) }]"
        @click="navigateDiagnostic(diagnostic)"
      >
        <template #prepend>
          <v-icon
            :color="diagnostic.severity === 'error' ? 'error' : 'warning'"
            :icon="diagnosticIcon[diagnostic.severity] || diagnosticIcon.info"
          />
        </template>

        <v-list-item-title>
          <v-chip
            class="severity-chip"
            :color="diagnostic.severity === 'error' ? 'error' : 'warning'"
            size="x-small"
            variant="tonal"
          >
            {{ diagnostic.severity }}
          </v-chip>
          {{ diagnostic.message }}
        </v-list-item-title>
        <v-list-item-subtitle>
          <span v-if="diagnostic.line">line {{ diagnostic.line }}</span>
          <span v-if="diagnostic.source"> · {{ diagnostic.source }}</span>
          <span v-if="diagnostic.group"> · group {{ diagnostic.group }}</span>
          <span v-if="diagnostic.key"> · key {{ diagnostic.key }}</span>
        </v-list-item-subtitle>

        <div class="diagnostic-guidance">
          <p>{{ diagnostic.nextStep }}</p>
          <div class="reference-links">
            <a
              v-for="reference in diagnostic.references"
              :key="reference.href"
              :href="reference.href"
              target="_blank"
              rel="noreferrer"
              @click.stop
            >
              {{ reference.label }}
            </a>
          </div>
        </div>
      </v-list-item>
    </v-list>

    <v-alert v-else type="success" variant="tonal">
      No warnings detected for the currently supported mappings.
    </v-alert>
  </v-card>
</template>
