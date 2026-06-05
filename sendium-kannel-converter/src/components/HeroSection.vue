<script setup>
import { mdiShieldLockOutline } from '@mdi/js';
</script>

<template>
  <section class="hero compact-hero">
    <div>
      <p class="eyebrow">Sendium migration tool</p>
      <h1>Kannel config converter</h1>
      <p class="hero-copy">
        Convert legacy Kannel config into Sendium starter files locally in your browser.
      </p>
    </div>

    <v-card class="privacy-card" elevation="0">
      <v-icon :icon="mdiShieldLockOutline" size="32" />
      <div>
        <strong>Pure client-side</strong>
        <span>No telemetry, no server parsing, no config upload.</span>
      </div>
    </v-card>
  </section>
</template>
