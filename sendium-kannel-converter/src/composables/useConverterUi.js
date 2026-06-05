import { computed, ref } from 'vue';
import { strToU8, zipSync } from 'fflate';
import { convertKannelConfig, SAMPLE_KANNEL_CONFIG, SAMPLE_SMPPBOX_CONFIG } from '../converter';
import { copyText, downloadBlob, downloadText } from '../utils/fileActions';

export function useConverterUi() {
  const source = ref(SAMPLE_KANNEL_CONFIG);
  const smppboxSource = ref('');
  const activeLegacyFile = ref('kannel.conf');
  const activeFile = ref('smsg.properties');
  const navigationTarget = ref(null);
  const notification = ref({ show: false, text: '', color: 'success' });

  const conversion = computed(() => convertKannelConfig(source.value, smppboxSource.value));
  const fileNames = computed(() => Object.keys(conversion.value.files));
  const activeOutput = computed(() => conversion.value.files[activeFile.value] || '');
  const outputLines = computed(() => toNumberedLines(activeOutput.value));

  function loadSample() {
    source.value = SAMPLE_KANNEL_CONFIG;
  }

  function loadSmppboxSample() {
    smppboxSource.value = SAMPLE_SMPPBOX_CONFIG;
    activeLegacyFile.value = 'smppbox.conf';
  }

  function clearSource() {
    source.value = '';
  }

  function clearSmppboxSource() {
    smppboxSource.value = '';
  }

  async function importKannelFile(file) {
    await importLegacyFile(file, 'kannel.conf');
  }

  async function importSmppboxFile(file) {
    await importLegacyFile(file, 'smppbox.conf');
  }

  async function importLegacyFile(file, sourceName) {
    try {
      const content = await file.text();

      if (sourceName === 'kannel.conf') {
        source.value = content;
      } else {
        smppboxSource.value = content;
      }

      activeLegacyFile.value = sourceName;
      notify(`Loaded ${file.name || sourceName} as ${sourceName}`, 'success');
    } catch {
      notify(`Could not read ${file.name || sourceName}. Use paste instead.`, 'error');
    }
  }

  function navigateToDiagnostic(diagnostic) {
    if (!diagnostic.source || !diagnostic.line) {
      return;
    }

    activeLegacyFile.value = diagnostic.source;
    navigationTarget.value = {
      id: Date.now(),
      source: diagnostic.source,
      line: diagnostic.line,
    };
  }

  async function copyActiveFile() {
    try {
      await copyText(activeOutput.value);
      notify(`Copied ${activeFile.value}`, 'success');
    } catch {
      notify('Could not copy file. Use manual selection instead.', 'error');
    }
  }

  function downloadActiveFile() {
    downloadText(activeFile.value, activeOutput.value);
    notify(`Downloaded ${activeFile.value}`, 'success');
  }

  function downloadBundle() {
    const zippedFiles = Object.fromEntries(
      Object.entries(conversion.value.files).map(([fileName, content]) => [fileName, strToU8(content)]),
    );

    downloadBlob('conf.zip', new Blob([zipSync(zippedFiles)], { type: 'application/zip' }));
    notify('Downloaded conf.zip', 'success');
  }

  function notify(text, color) {
    notification.value = { show: true, text, color };
  }

  return {
    source,
    smppboxSource,
    activeLegacyFile,
    activeFile,
    navigationTarget,
    notification,
    conversion,
    fileNames,
    activeOutput,
    outputLines,
    loadSample,
    loadSmppboxSample,
    clearSource,
    clearSmppboxSource,
    importKannelFile,
    importSmppboxFile,
    navigateToDiagnostic,
    copyActiveFile,
    downloadActiveFile,
    downloadBundle,
  };
}

function toNumberedLines(text) {
  const lines = text.split('\n');
  return (lines.length ? lines : ['']).map((content, index) => ({
    number: index + 1,
    content,
  }));
}
