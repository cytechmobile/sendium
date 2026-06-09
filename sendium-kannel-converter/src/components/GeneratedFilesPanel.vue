<script setup>
import { computed } from 'vue';
import { mdiContentCopy, mdiDownload, mdiDownloadBoxOutline } from '@mdi/js';
import SummaryMetrics from './SummaryMetrics.vue';

const props = defineProps({
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

const highlightedLines = computed(() => outputLinesWithTokens(activeFile.value));

function outputLinesWithTokens(fileName) {
  return props.outputLines.map((line) => ({
    ...line,
    tokens: tokenizeGeneratedLine(fileName, line.content),
  }));
}

function tokenizeGeneratedLine(fileName, content) {
  if (!content) {
    return [{ type: 'plain', text: ' ' }];
  }

  if (content.startsWith('#')) {
    return [{ type: 'comment', text: content }];
  }

  if (fileName === 'routingTable.conf') {
    return tokenizeRoutingLine(content);
  }

  if (fileName === 'smsg.properties') {
    return tokenizePropertiesLine(content);
  }

  if (fileName === 'credentials.yml') {
    return tokenizeYamlLine(content);
  }

  return [{ type: 'plain', text: content }];
}

function tokenizeRoutingLine(content) {
  if (/^\[[^\]]+\]$/.test(content)) {
    return [{ type: 'table', text: content }];
  }

  const parts = splitRoutingRule(content);
  if (!parts) {
    return [{ type: 'plain', text: content }];
  }

  return [
    ...tokenizeRoutingSegment(parts.target, 'target'),
    { type: 'separator', text: ':' },
    ...tokenizeRoutingSegment(parts.attribute, 'attribute'),
    { type: 'separator', text: ':' },
    ...tokenizeRoutingSegment(parts.operator, 'operator'),
    { type: 'separator', text: ':' },
    ...tokenizeRoutingSegment(parts.value, 'value'),
  ];
}

function splitRoutingRule(content) {
  const first = content.indexOf(':');
  const second = content.indexOf(':', first + 1);
  const third = content.indexOf(':', second + 1);

  if (first === -1 || second === -1 || third === -1) {
    return null;
  }

  return {
    target: content.slice(0, first),
    attribute: content.slice(first + 1, second),
    operator: content.slice(second + 1, third),
    value: content.slice(third + 1),
  };
}

function tokenizeRoutingSegment(segment, type) {
  return splitMultiRuleSegment(segment).map((text) => ({
    type: text === '~~' ? 'joiner' : type,
    text,
  }));
}

function splitMultiRuleSegment(segment) {
  return segment.split(/(~~)/g).filter((part) => part.length > 0);
}

function tokenizePropertiesLine(content) {
  const separatorIndex = content.indexOf('=');
  if (separatorIndex === -1) {
    return [{ type: 'plain', text: content }];
  }

  return [
    { type: 'property', text: content.slice(0, separatorIndex).trimEnd() },
    { type: 'plain', text: content.slice(content.slice(0, separatorIndex).trimEnd().length, separatorIndex) },
    { type: 'separator', text: '=' },
    { type: 'value', text: content.slice(separatorIndex + 1) },
  ];
}

function tokenizeYamlLine(content) {
  const match = content.match(/^(\s*)(-\s+)?([^:#]+:)(.*)$/);
  if (!match) {
    return [{ type: 'plain', text: content }];
  }

  const [, indent, marker = '', key, value] = match;
  return [
    { type: 'plain', text: indent },
    ...(marker ? [{ type: 'separator', text: marker }] : []),
    { type: 'property', text: key },
    { type: 'value', text: value },
  ];
}
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

    <div v-if="activeFile === 'routingTable.conf'" class="routing-legend" aria-label="Routing syntax legend">
      <span><span class="syntax-token token-target">target</span> destination worker or table</span>
      <span><span class="syntax-token token-attribute">attribute</span> message fields</span>
      <span><span class="syntax-token token-operator">operator</span> match policy</span>
      <span><span class="syntax-token token-value">value</span> expected values</span>
    </div>

    <div class="output-code" aria-label="Generated Sendium file preview">
      <div v-for="line in highlightedLines" :key="line.number" class="output-line">
        <span class="line-number">{{ line.number }}</span>
        <code>
          <span
            v-for="(token, tokenIndex) in line.tokens"
            :key="`${line.number}-${tokenIndex}`"
            class="syntax-token"
            :class="`token-${token.type}`"
          >{{ token.text }}</span>
        </code>
      </div>
    </div>
  </v-card>
</template>
