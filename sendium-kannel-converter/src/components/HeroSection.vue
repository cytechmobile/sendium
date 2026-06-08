<script setup>
import { mdiShieldLockOutline, mdiWeatherNight, mdiWhiteBalanceSunny } from '@mdi/js';

defineProps({
  isLightTheme: {
    type: Boolean,
    required: true,
  },
});

defineEmits(['toggle-appearance']);
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

    <div class="hero-side">
      <v-btn
        class="appearance-toggle"
        color="secondary"
        variant="tonal"
        :aria-label="isLightTheme ? 'Switch to dark mode' : 'Switch to light mode'"
        :prepend-icon="isLightTheme ? mdiWeatherNight : mdiWhiteBalanceSunny"
        @click="$emit('toggle-appearance')"
      >
        {{ isLightTheme ? 'Dark mode' : 'Light mode' }}
      </v-btn>

      <v-card class="privacy-card" elevation="0">
        <v-icon :icon="mdiShieldLockOutline" size="32" />
        <div>
          <strong>Pure client-side</strong>
          <span>No telemetry, no server parsing, no config upload.</span>
        </div>
      </v-card>
    </div>
  </section>
</template>
