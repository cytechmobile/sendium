<template>
  <v-container id="DLRPageId">
    <v-card>
      <v-card-title>
        DLR Status
        <v-spacer></v-spacer>
        <v-btn icon @click="fetchDlrStatus">
          <v-icon>mdi-refresh</v-icon>
        </v-btn>
      </v-card-title>
      <v-card-text>
        <v-data-table
          :headers="headers"
          :items="dlrItems"
          :loading="loading"
          class="elevation-1"
        >
          <template v-slot:item.receivedAt="{ item }">
            {{ formatDateTime(item.receivedAt) }}
          </template>
          <template v-slot:item.sentAt="{ item }">
            {{ formatDateTime(item.sentAt) }}
          </template>
          <template v-slot:item.forwardDate="{ item }">
            {{ formatDateTime(item.forwardDate) }}
          </template>
          <template v-slot:item.processedAt="{ item }">
            {{ formatDateTime(item.processedAt) }}
          </template>
        </v-data-table>
      </v-card-text>
    </v-card>
  </v-container>
</template>

<script>
import axios from 'axios'; // Using axios for HTTP requests

export default {
  name: 'DlrStatusPage',
  data() {
    return {
      loading: false,
      dlrItems: [],
      headers: [
        { title: 'Internal ID', value: 'forwardingId' },
        { title: 'Vendor ID', value: 'smscid' }, // (SMSC ID)
        { title: 'From', value: 'fromAddress' },
        { title: 'To', value: 'toAddress' },
        { title: 'Status', value: 'status' },
        { title: 'Received At', value: 'receivedAt' },
        { title: 'Sent At', value: 'sentAt' },
        { title: 'Processed At', value: 'processedAt' },
        { title: 'Forward Date', value: 'forwardDate' },
      ],
    };
  },
  methods: {
    async fetchDlrStatus() {
      this.loading = true;
      try {
        const apiKey = 'your-admin-api-key'; // Hardcoded API key
        const response = await axios.get('/api/dlr/status', {
          headers: {
            'X-API-Key': apiKey,
          },
        });
        this.dlrItems = response.data;
      } catch (error) {
        console.error('Error fetching DLR status:', error);
        // Handle error appropriately in a real app (e.g., show a notification)
        this.dlrItems = []; // Clear items on error
      } finally {
        this.loading = false;
      }
    },
    formatDateTime(dateTimeString) {
      if (!dateTimeString) return '';
      // Assuming dateTimeString is in ISO format (e.g., from Java LocalDateTime)
      // Adjust formatting as needed
      try {
        const options = {
          year: 'numeric', month: 'short', day: 'numeric',
          hour: '2-digit', minute: '2-digit', second: '2-digit',
        };
        return new Date(dateTimeString).toLocaleString(undefined, options);
      } catch (e) {
        console.warn('Error formatting date:', dateTimeString, e);
        return dateTimeString; // fallback to original string if formatting fails
      }
    },
  },
  mounted() {
    this.fetchDlrStatus();
  },
};
</script>

<style scoped>
/* Add any component-specific styles here */
</style>
