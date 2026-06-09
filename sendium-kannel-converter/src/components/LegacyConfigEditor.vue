<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue';

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

const LINE_TONE_PRIORITY = ['error', 'unsupported', 'runtime-needed', 'warning-only', 'mapped-active', 'warning'];

const LINE_MARKERS = {
  error: 'E',
  unsupported: 'U',
  'runtime-needed': 'R',
  'warning-only': 'W',
  'mapped-active': 'M',
  warning: '!',
  none: '',
};

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
    const tone = getLineTone(diagnostics);

    return {
      number,
      diagnostics,
      tone,
      title: diagnostics.map((diagnostic) => diagnostic.message).join('\n'),
    };
  });
});

function getLineTone(diagnostics) {
  if (diagnostics.length === 0) {
    return 'none';
  }

  return LINE_TONE_PRIORITY.find((tone) => diagnostics.some((diagnostic) => diagnosticTone(diagnostic) === tone)) || 'warning';
}

function diagnosticTone(diagnostic) {
  if (diagnostic.severity === 'error') {
    return 'error';
  }

  return diagnostic.routingStatus || diagnostic.severity || 'warning';
}

function lineMarker(line) {
  return LINE_MARKERS[line.tone] || '';
}

function syncGutterScroll(event) {
  syncGutterToTextarea(event.target);
}

function syncGutterToTextarea(textareaElement = textarea.value) {
  if (!gutter.value || !textareaElement) {
    return;
  }

  updateGutterScrollbarCompensation(textareaElement);
  gutter.value.scrollTop = textareaElement.scrollTop;
}

function updateGutterScrollbarCompensation(textareaElement = textarea.value) {
  if (!gutter.value || !textareaElement) {
    return;
  }

  const horizontalScrollbarHeight = Math.max(0, textareaElement.offsetHeight - textareaElement.clientHeight);
  gutter.value.style.setProperty('--editor-scrollbar-compensation', `${horizontalScrollbarHeight}px`);
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

watch(
  model,
  async () => {
    await nextTick();
    syncGutterToTextarea();
  },
  { immediate: true },
);

onMounted(() => {
  updateGutterScrollbarCompensation();
});

function scrollToLine(line) {
  if (!textarea.value) {
    return;
  }

  const style = window.getComputedStyle(textarea.value);
  const lineHeight = parseFloat(style.lineHeight) || parseFloat(style.fontSize) * 1.55;
  const scrollTop = Math.max(0, (line - 1) * lineHeight - textarea.value.clientHeight / 3);

  textarea.value.scrollTop = scrollTop;
  syncGutterToTextarea();

  textarea.value.focus({ preventScroll: true });
}
</script>

<template>
  <div class="legacy-editor">
    <div ref="gutter" class="editor-gutter" aria-hidden="true">
      <div
        v-for="line in editorLines"
        :key="line.number"
        :class="['editor-gutter-line', `editor-gutter-${line.tone}`]"
        :title="line.title"
      >
        <span class="editor-marker">{{ lineMarker(line) }}</span>
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
