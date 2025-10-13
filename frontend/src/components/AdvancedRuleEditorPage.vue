<template>
  <v-container fluid>
    <v-card>
      <v-card-title>Advanced Routing Rules Editor (JSON)</v-card-title>
      <v-card-subtitle>Edit the raw routing-rules.json file directly. Be cautious with changes.</v-card-subtitle>
      <v-card-text>
        <v-textarea
          v-model="jsonContent"
          label="JSON Rules"
          auto-grow
          rows="20"
          :loading="isLoading"
          :disabled="isLoading"
          variant="outlined"
          filled
        ></v-textarea>
      </v-card-text>
      <v-card-actions>
        <v-spacer></v-spacer>
        <v-btn
          color="primary"
          @click="saveJsonRules"
          :loading="isSaving"
          :disabled="isLoading || isSaving"
        >
          Save Changes
        </v-btn>
      </v-card-actions>
    </v-card>

    <v-snackbar
      v-model="snackbar.visible"
      :color="snackbar.color"
      :timeout="6000"
      location="top right"
    >
      {{ snackbar.text }}
      <template v-slot:actions>
        <v-btn color="white" variant="text" @click="snackbar.visible = false">
          Close
        </v-btn>
      </template>
    </v-snackbar>
  </v-container>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import axios from 'axios';

const jsonContent = ref('');
const isLoading = ref(false); // For initial loading
const isSaving = ref(false); // For save operation
const snackbar = ref({ visible: false, text: '', color: '' });

function showSnackbar(text, color = 'info') {
  snackbar.value = { visible: true, text, color };
}

async function fetchJsonRules() {
  isLoading.value = true;
  const apiKey = 'your-admin-api-key'; // Hardcoded API key
  const config = {
    headers: {
      'X-API-Key': apiKey,
    },
  };
  try {
    const response = await axios.get('/api/admin/routing-rules', config);
    // The backend should send plain text, but if it sends JSON, stringify it for the textarea.
    // If it's already a string (as expected from text/plain), use it directly.
    if (typeof response.data === 'string') {
      jsonContent.value = response.data;
    } else {
      jsonContent.value = JSON.stringify(response.data, null, 2);
    }
  } catch (error) {
    console.error('Error fetching JSON rules:', error);
    showSnackbar('Error fetching JSON rules: ' + (error.response?.data?.error || error.message), 'error');
    jsonContent.value = '// Could not load rules. See console for details or check server status.';
  } finally {
    isLoading.value = false;
  }
}

async function saveJsonRules() {
  isSaving.value = true;
  try {
    // Attempt to parse the JSON to ensure it's valid before sending
    // This provides client-side validation, though the server also validates.
    const data = JSON.parse(jsonContent.value);

    const apiKey = 'your-admin-api-key'; // Hardcoded API key
    const response = await axios.put('/api/admin/routing-rules', data, {
      headers: {
        'Content-Type': 'application/json',
        'X-API-Key': apiKey,
      },
    });
    showSnackbar(response.data.message || 'Rules saved successfully!', 'success');
    // Re-fetch to confirm changes and ensure the textarea has the canonical version (e.g., if server reformats)
    await fetchJsonRules();
  } catch (error) {
    console.error('Error saving JSON rules:', error);
    let errorMessage = 'Error saving JSON rules: ';
    if (error.response) { // Error from server (e.g. validation error)
        errorMessage += error.response.data?.error || error.message;
    } else if (error.message.startsWith("JSON.parse")) { // Error from local JSON.parse
        errorMessage += "Invalid JSON format. Please check your syntax.";
    } 
    else { // Other errors (e.g. network error)
        errorMessage += error.message;
    }
    showSnackbar(errorMessage, 'error');
  } finally {
    isSaving.value = false;
  }
}

onMounted(() => {
  fetchJsonRules();
});
</script>

<style scoped>
/* Add any specific styles if needed */
.v-textarea textarea {
  font-family: 'Courier New', Courier, monospace; /* Monospaced font for JSON editing */
}
</style>
