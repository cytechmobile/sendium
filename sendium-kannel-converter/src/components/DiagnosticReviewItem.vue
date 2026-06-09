<script setup>
import { mdiAlertOctagonOutline, mdiAlertOutline, mdiInformationOutline } from '@mdi/js';

const props = defineProps({
  diagnostic: {
    type: Object,
    required: true,
  },
});

const emit = defineEmits(['navigate']);

const diagnosticIcon = {
  error: mdiAlertOctagonOutline,
  warning: mdiAlertOutline,
  info: mdiInformationOutline,
};

const ROUTING_STATUS_LABELS = {
  'mapped-active': 'mapped active',
  'warning-only': 'warning only',
  unsupported: 'unsupported',
  'runtime-needed': 'runtime needed',
};

const ROUTING_STATUS_COLORS = {
  'mapped-active': 'success',
  'warning-only': 'warning',
  unsupported: 'error',
  'runtime-needed': 'info',
};

function diagnosticTone(diagnostic) {
  if (diagnostic.severity === 'error') {
    return 'error';
  }

  return diagnostic.routingStatus || diagnostic.severity;
}

function diagnosticClass(diagnostic) {
  return `diagnostic-item diagnostic-${diagnosticTone(diagnostic)}`;
}

function diagnosticColor(diagnostic) {
  if (diagnostic.severity === 'error') {
    return 'error';
  }

  return ROUTING_STATUS_COLORS[diagnostic.routingStatus] || 'warning';
}

function diagnosticLabel(diagnostic) {
  if (diagnostic.severity === 'error') {
    return 'error';
  }

  return ROUTING_STATUS_LABELS[diagnostic.routingStatus] || diagnostic.severity;
}

function canNavigateDiagnostic(diagnostic) {
  return Boolean(diagnostic.source && diagnostic.line);
}

function navigateDiagnostic() {
  if (canNavigateDiagnostic(props.diagnostic)) {
    emit('navigate', props.diagnostic);
  }
}
</script>

<template>
  <v-list-item
    :class="[diagnosticClass(diagnostic), { 'diagnostic-navigable': canNavigateDiagnostic(diagnostic) }]"
    @click="navigateDiagnostic"
  >
    <template #prepend>
      <v-icon
        :color="diagnosticColor(diagnostic)"
        :icon="diagnosticIcon[diagnostic.severity] || diagnosticIcon.info"
      />
    </template>

    <v-list-item-title>
      <v-chip
        class="severity-chip"
        :color="diagnosticColor(diagnostic)"
        size="x-small"
        variant="tonal"
      >
        {{ diagnosticLabel(diagnostic) }}
      </v-chip>
      {{ diagnostic.message }}
    </v-list-item-title>
    <v-list-item-subtitle>
      <span v-if="diagnostic.line">line {{ diagnostic.line }}</span>
      <span v-if="diagnostic.source"> · {{ diagnostic.source }}</span>
      <span v-if="diagnostic.group"> · group {{ diagnostic.group }}</span>
      <span v-if="diagnostic.key"> · key {{ diagnostic.key }}</span>
    </v-list-item-subtitle>

    <div v-if="diagnostic.nextStep || diagnostic.references?.length" class="diagnostic-guidance">
      <p v-if="diagnostic.nextStep">{{ diagnostic.nextStep }}</p>
      <div v-if="diagnostic.references?.length" class="reference-links">
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
</template>
