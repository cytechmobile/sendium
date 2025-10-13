<template>
  <div>
    <h2>Vendor Configuration</h2>
    <div v-if="message" :class="messageType === 'error' ? 'errorMessage' : 'successMessage'">
      {{ message }}
    </div>
    <textarea v-model="configText" placeholder="Loading SMPP Vendor configuration..." rows="25" cols="120"></textarea>
    <br />
    <button @click="saveConfig" :disabled="isLoading">
      {{ isLoading ? 'Saving...' : 'Save Configuration' }}
    </button>
    <button @click="fetchConfig" :disabled="isLoading" style="margin-left: 10px;">
      {{ isLoading ? 'Loading...' : 'Reload Configuration' }}
    </button>
  </div>
</template>

<script>
import axios from "axios";

export default {
  name: 'VendorConfigEditorPage',
  data() {
    return {
      configText: '',
      message: '',
      messageType: '', // 'success' or 'error'
      isLoading: false,
    };
  },
  mounted() {
    this.fetchConfig();
  },
  methods: {
    async fetchConfig() {
      this.isLoading = true;
      this.message = '';
      this.messageType = '';
      const apiKey = 'your-admin-api-key'; // Hardcoded API key
      const config = {
        headers: {
          'X-API-Key': apiKey,
        },
      };
      try {
        const response = await fetch('/api/admin/vendors', config);
        if (response.ok) {
          this.configText = await response.text();
          this.message = 'Configuration loaded successfully.';
          this.messageType = 'success';
        } else {
          const errorData = await response.json().catch(() => ({ error: 'Failed to parse error response.' }));
          this.message = `Failed to load configuration: ${response.status} ${response.statusText}. ${errorData.error || ''}`;
          this.messageType = 'error';
          this.configText = '// Failed to load configuration. Please check server logs and try again.';
        }
      } catch (error) {
        console.error('Error fetching SMPP vendor config:', error);
        this.message = `Error fetching configuration: ${error.message}`;
        this.messageType = 'error';
        this.configText = '// An error occurred while fetching configuration. Please check console and server logs.';
      } finally {
        this.isLoading = false;
      }
    },
    async saveConfig() {
      this.isLoading = true;
      this.message = '';
      this.messageType = '';
      try {
        const response = await fetch('/api/admin/vendors', {
          method: 'PUT',
          headers: {
            'Content-Type': 'text/plain',
          },
          body: this.configText,
        });
        
        const responseData = await response.json().catch(() => null); // Try to parse JSON, but allow non-JSON responses too

        if (response.ok) {
          this.message = responseData?.message || 'Configuration saved successfully.';
          this.messageType = 'success';
          // Optionally re-fetch to confirm or rely on the PUT response
          // await this.fetchConfig(); 
        } else {
          this.message = `Failed to save configuration: ${response.status} ${response.statusText}. ${responseData?.error || responseData?.message || ''}`;
          this.messageType = 'error';
        }
      } catch (error) {
        console.error('Error saving SMPP vendor config:', error);
        this.message = `Error saving configuration: ${error.message}`;
        this.messageType = 'error';
      } finally {
        this.isLoading = false;
      }
    },
  },
};
</script>

<style scoped>
.errorMessage {
  color: red;
  background-color: #ffe0e0;
  border: 1px solid red;
  padding: 10px;
  margin-bottom: 15px;
  border-radius: 4px;
}
.successMessage {
  color: green;
  background-color: #e0ffe0;
  border: 1px solid green;
  padding: 10px;
  margin-bottom: 15px;
  border-radius: 4px;
}
textarea {
  width: 90%;
  font-family: monospace;
  border: 1px solid #ccc;
  padding: 10px;
  box-sizing: border-box;
}
button {
  padding: 10px 15px;
  background-color: #007bff;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 16px;
}
button:disabled {
  background-color: #cccccc;
  cursor: not-allowed;
}
button:hover:not(:disabled) {
  background-color: #0056b3;
}
</style>
