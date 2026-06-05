<script setup>
import HeroSection from './components/HeroSection.vue';
import ConverterWorkspace from './components/ConverterWorkspace.vue';
import DiagnosticsPanel from './components/DiagnosticsPanel.vue';
import MigrationChecklist from './components/MigrationChecklist.vue';
import { useConverterUi } from './composables/useConverterUi';

const ui = useConverterUi();
</script>

<template>
  <v-app>
    <v-main class="app-shell">
      <v-container fluid class="content-wrap">
        <HeroSection />

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
          <div class="review-heading-block">
            <p class="panel-kicker">Migration review</p>
            <h2>Resolve warnings and manual steps</h2>
            <p>
              Warnings do not block output, but they identify behavior that should be moved to Sendium config,
              routing, deployment, or application code.
            </p>
          </div>
          <v-row align="start">
            <v-col cols="12" lg="7">
              <DiagnosticsPanel
                :diagnostics="ui.conversion.value.diagnostics"
                :summary="ui.conversion.value.summary"
                @navigate-diagnostic="ui.navigateToDiagnostic"
              />
            </v-col>
            <v-col cols="12" lg="5">
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
