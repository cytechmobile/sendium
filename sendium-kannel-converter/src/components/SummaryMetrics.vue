<script setup>
defineProps({
  summary: {
    type: Object,
    required: true,
  },
});
</script>

<template>
  <v-row class="metrics-row">
    <v-col cols="6" md="3">
      <v-card class="metric-card" elevation="0">
        <span>Credentials : </span>
        <strong>{{ summary.credentials }}</strong>
      </v-card>
    </v-col>
    <v-col cols="6" md="3">
      <v-card class="metric-card" elevation="0">
        <span>SMPP clients : </span>
        <strong>{{ summary.smppClients }}</strong>
      </v-card>
    </v-col>
    <v-col cols="6" md="3">
      <v-card class="metric-card" elevation="0">
        <span>SMPP servers : </span>
        <strong>{{ summary.smppServers }}</strong>
      </v-card>
    </v-col>
    <v-col cols="6" md="3">
      <v-card class="metric-card" elevation="0">
        <span>Warnings/errors : </span>
        <strong>{{ summary.warnings }}/{{ summary.errors }}</strong>
      </v-card>
    </v-col>
  </v-row>
</template>
