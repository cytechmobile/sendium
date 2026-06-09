<script setup>
import { computed, ref } from 'vue';
import DiagnosticReviewItem from './DiagnosticReviewItem.vue';

const props = defineProps({
  compatibility: {
    type: Object,
    required: true,
  },
  diagnostics: {
    type: Array,
    required: true,
  },
});

const emit = defineEmits(['navigate-entry']);

const expandedStatuses = ref(new Set());

const STATUS_LABELS = {
  'mapped-active': 'Active starter mappings',
  'warning-only': 'Warning-only cases',
  unsupported: 'Unsupported routing cases',
  'runtime-needed': 'Needs runtime/app support',
};

const STATUS_ITEM_LABELS = {
  'mapped-active': 'mapped active',
  'warning-only': 'warning only',
  unsupported: 'unsupported',
  'runtime-needed': 'runtime needed',
};

const STATUS_COLORS = {
  'mapped-active': 'success',
  'warning-only': 'warning',
  unsupported: 'error',
  'runtime-needed': 'info',
};

const compatibilityDiagnostics = computed(() => (props.diagnostics || []).filter((diagnostic) => diagnostic.routingStatus));

const routingEntries = computed(() => (props.compatibility.entries || []).map(toDiagnosticItem));

const statusItems = computed(() => Object.entries(STATUS_LABELS).map(([status, label]) => {
  const diagnostics = routingEntries.value.filter((entry) => entry.routingStatus === status);
  return {
    status,
    label,
    color: STATUS_COLORS[status],
    count: diagnostics.length,
    diagnostics,
  };
}));

function toDiagnosticItem(entry) {
  const diagnostic = findMatchingDiagnostic(entry);

  return {
    severity: diagnostic?.severity || (entry.status === 'mapped-active' ? 'info' : 'warning'),
    source: entry.source,
    line: entry.line,
    group: entry.group,
    key: entry.key,
    routingCompatibilityId: entry.id,
    routingStatus: entry.status,
    routingCategory: entry.category,
    routingSurfaces: entry.sendiumSurfaces,
    message: diagnostic?.message || `${entryTitle(entry)} is ${STATUS_ITEM_LABELS[entry.status] || entry.status}: ${entry.note}`,
    nextStep: diagnostic?.nextStep || entry.note,
    references: diagnostic?.references || [],
  };
}

function findMatchingDiagnostic(entry) {
  const exactMatch = compatibilityDiagnostics.value.find((diagnostic) => isSameSource(diagnostic, entry)
    && diagnostic.routingStatus === entry.status);

  return exactMatch || compatibilityDiagnostics.value.find((diagnostic) => isSameSource(diagnostic, entry)) || null;
}

function isSameSource(diagnostic, entry) {
  return diagnostic.source === entry.source
    && diagnostic.line === entry.line
    && diagnostic.group === entry.group
    && (diagnostic.key || null) === (entry.key || null);
}

function entryTitle(entry) {
  return entry.key ? `${entry.group} / ${entry.key}` : `group = ${entry.group}`;
}

function isStatusExpanded(status) {
  return expandedStatuses.value.has(status);
}

function toggleStatus(status) {
  const nextStatuses = new Set(expandedStatuses.value);
  if (nextStatuses.has(status)) {
    nextStatuses.delete(status);
  } else {
    nextStatuses.add(status);
  }
  expandedStatuses.value = nextStatuses;
}

function navigateEntry(entry) {
  emit('navigate-entry', entry);
}
</script>

<template>
  <v-card class="guidance-card routing-compatibility-card" elevation="0">
    <p class="panel-kicker">Routing compatibility</p>
    <h2>Kannel routing coverage</h2>
    <p>
      Active mappings are generated as starter rules. Warning-only and runtime-needed cases stay as review items so
      ambiguous Kannel behavior is not silently converted.
    </p>

    <div class="routing-status-grid" aria-label="Routing compatibility status summary">
      <section
        v-for="item in statusItems"
        :key="item.status"
        :class="['review-group', `routing-entry-${item.status}`]"
      >
        <button
          type="button"
          class="review-group-header routing-status-header"
          :disabled="item.count === 0"
          :aria-expanded="isStatusExpanded(item.status)"
          @click="toggleStatus(item.status)"
        >
          <span>
            <strong>{{ item.count }}</strong>
            {{ item.label }}
          </span>
          <v-chip :color="item.color" size="small" variant="tonal">
            {{ item.count === 0 ? 'None' : isStatusExpanded(item.status) ? 'Hide' : 'Show' }}
          </v-chip>
        </button>

        <v-list v-if="item.count > 0 && isStatusExpanded(item.status)" class="diagnostic-list review-group-items routing-status-items" lines="three">
          <DiagnosticReviewItem
            v-for="(diagnostic, index) in item.diagnostics"
            :key="`${diagnostic.routingCompatibilityId}-${diagnostic.source || 'global'}-${diagnostic.line || 'global'}-${index}`"
            :diagnostic="diagnostic"
            @navigate="navigateEntry"
          />
        </v-list>
      </section>
    </div>
  </v-card>
</template>
