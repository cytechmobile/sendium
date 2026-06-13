<script setup>
import { computed, nextTick, ref, watch } from 'vue';

const props = defineProps({
  diagnostics: {
    type: Array,
    required: true,
  },
  sourceName: {
    type: String,
    required: true,
  },
  placeholder: {
    type: String,
    default: '',
  },
  rows: {
    type: Number,
    default: 28,
  },
  navigationTarget: {
    type: Object,
    default: null,
  },
});

const model = defineModel({ type: String, required: true });
const gutter = ref(null);
const textarea = ref(null);

const editorLines = computed(() => {
  const diagnosticsByLine = props.diagnostics.reduce((lines, diagnostic) => {
    if (!diagnostic.line || diagnostic.source !== props.sourceName) {
      return lines;
    }

    const existing = lines.get(diagnostic.line) || [];
    existing.push(diagnostic);
    lines.set(diagnostic.line, existing);
    return lines;
  }, new Map());

  const lineCount = Math.max(model.value.split('\n').length, props.rows);

  return Array.from({ length: lineCount }, (_, index) => {
    const number = index + 1;
    const diagnostics = diagnosticsByLine.get(number) || [];
    const severity = diagnostics.some((diagnostic) => diagnostic.severity === 'error')
      ? 'error'
      : diagnostics.length > 0
        ? 'warning'
        : 'none';

    return {
      number,
      diagnostics,
      severity,
      title: diagnostics.map((diagnostic) => diagnostic.message).join('\n'),
    };
  });
});

function syncGutterScroll(event) {
  if (gutter.value) {
    gutter.value.scrollTop = event.target.scrollTop;
  }
}

watch(
  () => props.navigationTarget,
  async (target) => {
    if (!target || target.source !== props.sourceName || !target.line) {
      return;
    }

    await nextTick();
    scrollToLine(target.line);
  },
  { immediate: true },
);

function scrollToLine(line) {
  if (!textarea.value) {
    return;
  }

  const style = window.getComputedStyle(textarea.value);
  const lineHeight = parseFloat(style.lineHeight) || parseFloat(style.fontSize) * 1.55;
  const scrollTop = Math.max(0, (line - 1) * lineHeight - textarea.value.clientHeight / 3);

  textarea.value.scrollTop = scrollTop;
  if (gutter.value) {
    gutter.value.scrollTop = scrollTop;
  }

  textarea.value.focus({ preventScroll: true });
}
</script>

<template>
  <div class="legacy-editor">
    <div ref="gutter" class="editor-gutter" aria-hidden="true">
      <div
        v-for="line in editorLines"
        :key="line.number"
        :class="['editor-gutter-line', `editor-gutter-${line.severity}`]"
        :title="line.title"
      >
        <span class="editor-marker">{{ line.severity === 'error' ? 'E' : line.severity === 'warning' ? '!' : '' }}</span>
        <span class="editor-line-number">{{ line.number }}</span>
      </div>
    </div>

    <textarea
      ref="textarea"
      v-model="model"
      class="legacy-textarea"
      :rows="rows"
      :placeholder="placeholder"
      :aria-label="`${sourceName} editor`"
      spellcheck="false"
      wrap="off"
      @scroll="syncGutterScroll"
    />
  </div>
</template>
