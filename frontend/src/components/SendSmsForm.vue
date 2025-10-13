<template>
  <v-container>
    <v-row justify="center">
      <v-col cols="12" md="8" lg="6">
        <v-card>
          <v-card-title class="headline">Send New SMS</v-card-title>
          <v-card-text>
            <v-form ref="form" @submit.prevent="sendMessage">
              <v-text-field
                v-model="to"
                label="Recipient Phone Number (To)"
                :rules="[rules.required, rules.phone]"
                required
                prepend-icon="mdi-phone"
              ></v-text-field>

              <v-text-field
                v-model="from"
                label="Sender ID (From)"
                :rules="[rules.required]"
                required
                prepend-icon="mdi-account-arrow-right"
              ></v-text-field>

              <v-textarea
                v-model="text"
                label="Message Content"
                :rules="[rules.required]"
                required
                prepend-icon="mdi-message-text"
                auto-grow
                rows="3"
              ></v-textarea>

              <v-btn
                type="submit"
                color="primary"
                :loading="loading"
                :disabled="loading"
                block
                class="mt-4"
              >
                Send Message
                <template v-slot:loader>
                  <v-progress-linear indeterminate></v-progress-linear>
                </template>
              </v-btn>
            </v-form>
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>

    <v-snackbar
      v-model="snackbar.visible"
      :color="snackbar.color"
      :timeout="snackbar.timeout"
      location="top right"
    >
      {{ snackbar.message }}
      <template v-slot:actions>
        <v-btn color="white" variant="text" @click="snackbar.visible = false">
          Close
        </v-btn>
      </template>
    </v-snackbar>
  </v-container>
</template>

<script setup>
import { ref, reactive } from 'vue';
import axios from 'axios'; // Import axios

// Form data refs
const to = ref('');
const from = ref('');
const text = ref('');
const form = ref(null); // Ref for the v-form component

// UI state refs
const loading = ref(false);
const snackbar = reactive({
  visible: false,
  message: '',
  color: 'success',
  timeout: 6000,
});

// Validation rules
const rules = {
  required: value => !!value || 'This field is required.',
  phone: value => { // Basic phone validation example
    const pattern = /^\+?[1-9]\d{1,14}$/; // E.164 like pattern, very basic
    return pattern.test(value) || 'Invalid phone number format (e.g., +1234567890).';
  }
};

// Function to display snackbar messages
const showSnackbar = (message, color = 'success', timeout = 6000) => {
  snackbar.message = message;
  snackbar.color = color;
  snackbar.timeout = timeout;
  snackbar.visible = true;
};

// Function to handle form submission
const sendMessage = async () => {
  const { valid } = await form.value.validate();

  if (!valid) {
    showSnackbar('Please correct the form errors.', 'error');
    return;
  }

  loading.value = true;
  try {
    const apiKey = 'your-admin-api-key'; // Hardcoded API key
    const response = await axios.post('/api/sms/send', { // Corrected endpoint
      to: to.value,
      from: from.value,
      text: text.value,
    }, {
      headers: {
        'X-API-Key': apiKey,
      },
    });

    // Assuming backend returns 200/201/202 for success
    showSnackbar(response.data.detail || 'Message sent successfully!', 'success');
    // Optionally clear the form
    to.value = '';
    from.value = '';
    text.value = '';
    form.value.resetValidation(); // Reset validation state
    // form.value.reset(); // Resets form fields too, if preferred

  } catch (error) {
    let errorMessage = 'An unexpected error occurred while sending the message.';
    if (error.response && error.response.data && error.response.data.message) {
      errorMessage = error.response.data.message;
    } else if (error.response && error.response.data && error.response.data.detail) { // Handle Quarkus error details
        errorMessage = error.response.data.detail;
    } else if (error.message) {
      errorMessage = error.message;
    }
    showSnackbar(errorMessage, 'error');
    console.error('Error sending SMS:', error);
  } finally {
    loading.value = false;
  }
};
</script>

<style scoped>
/* Add any component-specific styles here */
.v-card {
  margin-top: 20px;
}
</style>
