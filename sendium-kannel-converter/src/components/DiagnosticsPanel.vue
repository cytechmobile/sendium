<script setup>
import { computed, ref } from 'vue';
import DiagnosticReviewItem from './DiagnosticReviewItem.vue';

const props = defineProps({
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

const expandedGroups = ref(new Set());

const REVIEW_GROUPS = [
  { id: 'error', label: 'Errors', color: 'error' },
  { id: 'warning', label: 'Warnings', color: 'warning' },
  { id: 'info', label: 'Info', color: 'info' },
];

const nonRoutingDiagnostics = computed(() => props.diagnostics.filter((diagnostic) => !diagnostic.routingStatus));

const reviewGroups = computed(() => REVIEW_GROUPS
  .map((group) => ({
    ...group,
    diagnostics: nonRoutingDiagnostics.value.filter((diagnostic) => diagnostic.severity === group.id),
  }))
  .filter((group) => group.diagnostics.length > 0));

const hasErrors = computed(() => nonRoutingDiagnostics.value.some((diagnostic) => diagnostic.severity === 'error'));

function isGroupExpanded(groupId) {
  return expandedGroups.value.has(groupId);
}

function toggleGroup(groupId) {
  const nextGroups = new Set(expandedGroups.value);
  if (nextGroups.has(groupId)) {
    nextGroups.delete(groupId);
  } else {
    nextGroups.add(groupId);
  }
  expandedGroups.value = nextGroups;
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
      <v-chip :color="hasErrors ? 'error' : 'success'" variant="tonal">
        {{ nonRoutingDiagnostics.length }} items
      </v-chip>
    </div>

    <div v-if="reviewGroups.length" class="review-group-list" aria-label="Warnings and manual steps groups">
      <section v-for="group in reviewGroups" :key="group.id" class="review-group">
        <button
          type="button"
          class="review-group-header"
          :aria-expanded="isGroupExpanded(group.id)"
          @click="toggleGroup(group.id)"
        >
          <span>
            <strong>{{ group.diagnostics.length }}</strong>
            {{ group.label }}
          </span>
          <v-chip :color="group.color" size="small" variant="tonal">
            {{ isGroupExpanded(group.id) ? 'Hide' : 'Show' }}
          </v-chip>
        </button>

        <v-list v-if="isGroupExpanded(group.id)" class="diagnostic-list review-group-items" lines="three">
          <DiagnosticReviewItem
            v-for="(diagnostic, index) in group.diagnostics"
            :key="`${diagnostic.source || 'global'}-${diagnostic.line || 'global'}-${index}`"
            :diagnostic="diagnostic"
            @navigate="navigateDiagnostic"
          />
        </v-list>
      </section>
    </div>

    <v-alert v-else type="success" variant="tonal">
      No non-routing warnings detected for the currently supported mappings.
    </v-alert>
  </v-card>
</template>
