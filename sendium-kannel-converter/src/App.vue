<script setup>
import { computed } from 'vue';
import { useTheme } from 'vuetify';
import HeroSection from './components/HeroSection.vue';
import ConverterWorkspace from './components/ConverterWorkspace.vue';
import DiagnosticsPanel from './components/DiagnosticsPanel.vue';
import MigrationChecklist from './components/MigrationChecklist.vue';
import RoutingCompatibilitySummary from './components/RoutingCompatibilitySummary.vue';
import { useConverterUi } from './composables/useConverterUi';

const THEME_STORAGE_KEY = 'sendium-kannel-converter-theme';
const DARK_THEME = 'sendiumDark';
const LIGHT_THEME = 'sendiumLight';

const ui = useConverterUi();
const theme = useTheme();
setTheme(getInitialTheme());

const isLightTheme = computed(() => theme.global.name.value === LIGHT_THEME);

function toggleAppearance() {
  const nextTheme = isLightTheme.value ? DARK_THEME : LIGHT_THEME;
  setTheme(nextTheme);

  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, nextTheme);
  } catch {
    // Theme persistence is optional; conversion stays fully local either way.
  }
}

function setTheme(nextTheme) {
  theme.global.name.value = nextTheme;

  if (typeof document !== 'undefined') {
    document.documentElement.dataset.appearance = nextTheme === LIGHT_THEME ? 'light' : 'dark';
  }
}

function getInitialTheme() {
  if (typeof window === 'undefined') {
    return DARK_THEME;
  }

  try {
    const storedTheme = window.localStorage.getItem(THEME_STORAGE_KEY);
    if ([DARK_THEME, LIGHT_THEME].includes(storedTheme)) {
      return storedTheme;
    }
  } catch {
    return DARK_THEME;
  }

  return window.matchMedia?.('(prefers-color-scheme: light)').matches ? LIGHT_THEME : DARK_THEME;
}
</script>

<template>
  <v-app>
    <v-main class="app-shell">
      <v-container fluid class="content-wrap">
        <HeroSection :is-light-theme="isLightTheme" @toggle-appearance="toggleAppearance" />

        <section class="workspace-section">
          <ConverterWorkspace
            v-model:active-legacy-file="ui.activeLegacyFile.value"
            v-model:active-file="ui.activeFile.value"
            v-model:kannel-source="ui.source.value"
            v-model:smppbox-source="ui.smppboxSource.value"
            :file-names="ui.fileNames.value"
            :output-lines="ui.outputLines.value"
            :summary="ui.conversion.value.summary"
            :diagnostics="ui.conversion.value.diagnostics"
            :navigation-target="ui.navigationTarget.value"
            @load-kannel-sample="ui.loadSample"
            @clear-kannel="ui.clearSource"
            @import-kannel-file="ui.importKannelFile"
            @load-smppbox-sample="ui.loadSmppboxSample"
            @clear-smppbox="ui.clearSmppboxSource"
            @import-smppbox-file="ui.importSmppboxFile"
            @copy-active="ui.copyActiveFile"
            @download-active="ui.downloadActiveFile"
            @download-bundle="ui.downloadBundle"
          />
        </section>

        <section class="workflow-section">
          <v-row align="start">
            <v-col cols="12" lg="6">
              <DiagnosticsPanel
                :diagnostics="ui.conversion.value.diagnostics"
                :summary="ui.conversion.value.summary"
                @navigate-diagnostic="ui.navigateToDiagnostic"
              />
            </v-col>
            <v-col cols="12" lg="6">
              <RoutingCompatibilitySummary
                :compatibility="ui.conversion.value.routingCompatibility"
                :diagnostics="ui.conversion.value.diagnostics"
                @navigate-entry="ui.navigateToDiagnostic"
              />
              <MigrationChecklist />
            </v-col>
          </v-row>
        </section>
      </v-container>

      <v-snackbar v-model="ui.notification.value.show" :color="ui.notification.value.color" timeout="2200">
        {{ ui.notification.value.text }}
      </v-snackbar>
    </v-main>
  </v-app>
</template>
