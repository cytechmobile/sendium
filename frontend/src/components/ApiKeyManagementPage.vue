<template>
  <v-container>
    <v-card>
      <v-card-title class="headline d-flex align-center">
        API Key Management
        <v-spacer></v-spacer>
        <v-btn icon="mdi-refresh" variant="text" @click="fetchApiKeys(true)" title="Refresh Keys" data-testid="refresh-keys-btn"></v-btn>
        <v-btn icon="mdi-plus" variant="text" @click="addItem" title="Add New Key" data-testid="add-key-btn"></v-btn>
      </v-card-title>
      <v-card-subtitle>Manage API keys</v-card-subtitle>
      <v-card-text>
        <v-data-table
            :headers="headers"
            :items="apiKeys"
            :loading="loading"
            class="elevation-0"
            :item-value="getItemValue"
            data-testid="api-keys-table"
        >
          <!-- Custom row properties to add data-testid to each <tr> -->
          <template v-slot:item="{ item }">
            <tr :data-testid="`api-key-row-${getItemValue(item)}`">
              <td>
                <v-chip :color="getColorForType(item.type)" label size="small" class="text-uppercase font-weight-bold">
                  {{ item.type }}
                </v-chip>
              </td>
              <td>
                <div v-if="item.type === 'smpp'">
                  <strong>System ID:</strong> {{ item.systemId }}
                </div>
                <div v-else class="d-flex align-center">
                  <span class="font-weight-bold mr-2">
                    {{ revealedKeys[getItemValue(item)] ? item.key : (item.key || '').substring(0, 8) + '...' }}
                  </span>
                  <v-icon
                      small
                      @click="toggleKeyVisibility(item)"
                      :title="revealedKeys[getItemValue(item)] ? 'Hide Key' : 'Show Key'"
                      :data-testid="`toggle-visibility-btn-${getItemValue(item)}`"
                  >
                    {{ revealedKeys[getItemValue(item)] ? 'mdi-eye-off' : 'mdi-eye' }}
                  </v-icon>
                </div>
              </td>
              <td>
                <div v-if="item.type === 'smpp'" class="d-flex align-center">
                  <span class="mr-2">
                    {{ showSmppPassword ? item.password : '••••••••' }}
                  </span>
                  <v-icon
                      small
                      @click="showSmppPassword = !showSmppPassword"
                      :title="showSmppPassword ? 'Hide Password' : 'Show Password'"
                      :data-testid="`toggle-smpp-password-btn-${getItemValue(item)}`"
                  >
                    {{ showSmppPassword ? 'mdi-eye-off' : 'mdi-eye' }}
                  </v-icon>
                </div>
              </td>
              <td class="text-end">
                <v-icon small class="mr-2" @click="editItem(item)" title="Edit Key" :data-testid="`edit-btn-${getItemValue(item)}`">
                  mdi-pencil
                </v-icon>
                <v-icon small @click="deleteItem(item)" title="Delete Key" color="error" :data-testid="`delete-btn-${getItemValue(item)}`">
                  mdi-delete
                </v-icon>
              </td>
            </tr>
          </template>

          <template v-slot:loading>
            <v-skeleton-loader type="table-row@3"></v-skeleton-loader>
          </template>
        </v-data-table>
      </v-card-text>
    </v-card>

    <v-dialog v-model="dialog" persistent max-width="600px">
      <v-card data-testid="add-edit-dialog">
        <v-card-title>
          <span class="headline">{{ formTitle }}</span>
        </v-card-title>
        <v-card-text>
          <v-container>
            <v-form ref="form" v-model="validForm">
              <v-select
                  v-if="editedIndex === -1"
                  v-model="editedItem.type"
                  :items="keyTypes"
                  label="Key Type"
                  :rules="[rules.required]"
                  required
                  outlined
                  dense
                  class="mb-3"
                  data-testid="key-type-select"
              ></v-select>

              <v-text-field
                  v-if="editedItem.type === 'admin' || editedItem.type === 'message'"
                  v-model="editedItem.key"
                  label="API Key"
                  :rules="[rules.required, rules.isIdentifierUnique]"
                  required
                  outlined
                  dense
                  :id="'api-key-input'"
              ></v-text-field>

              <template v-if="editedItem.type === 'smpp'">
                <v-text-field
                    v-model="editedItem.systemId"
                    label="SMPP System ID"
                    :rules="[rules.required, rules.isIdentifierUnique]"
                    required
                    outlined
                    dense
                    class="mb-3"
                    :id="'smpp-system-id-input'"
                ></v-text-field>
                <v-text-field
                    v-model="editedItem.password"
                    label="SMPP Password"
                    :rules="[rules.required]"
                    required
                    outlined
                    dense
                    type="password"
                    :id="'smpp-password-input'"
                ></v-text-field>
              </template>
            </v-form>
          </v-container>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn color="blue darken-1" text @click="closeDialog" data-testid="dialog-cancel-btn">Cancel</v-btn>
          <v-btn color="primary" :disabled="!validForm || loading" :loading="loading" @click="save" data-testid="dialog-save-btn">Save</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="deleteDialog" persistent max-width="400px">
      <v-card data-testid="delete-confirm-dialog">
        <v-card-title class="headline">Confirm Deletion</v-card-title>
        <v-card-text>Are you sure you want to delete this API key? This action cannot be undone.</v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn color="blue darken-1" text @click="closeDeleteDialog" data-testid="delete-dialog-cancel-btn">Cancel</v-btn>
          <v-btn color="error" :loading="loading" @click="deleteItemConfirm" data-testid="delete-dialog-confirm-btn">Delete</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-snackbar v-model="snackbar.show" :color="snackbar.color" :timeout="snackbar.timeout" location="top" data-testid="snackbar">
      {{ snackbar.text }}
      <template v-slot:actions>
        <v-btn color="white" variant="text" @click="snackbar.show = false">
          Close
        </v-btn>
      </template>
    </v-snackbar>
  </v-container>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue';
import axios from 'axios';

const apiKeys = ref([]);
const dialog = ref(false);
const deleteDialog = ref(false);
const validForm = ref(false);
const loading = ref(false);
const form = ref(null);
const revealedKeys = ref({});
const showSmppPassword = ref(false);

const defaultItem = ref({ type: null, key: '', systemId: '', password: '' });
const editedItem = ref({ ...defaultItem.value });
const itemToDelete = ref(null);
const editedIndex = ref(-1);

const snackbar = ref({
  show: false,
  text: '',
  color: '',
  timeout: 3000,
});

function getItemValue(item) {
  if (!item) return undefined;
  return item.type === 'smpp' ? item.systemId : item.key;
}

function extractErrorMessage(error, defaultMessage) {
  if (error.response && error.response.data) {
    if (error.response.data.message) return error.response.data.message;
    if (error.response.data.error) return error.response.data.error;
    return typeof error.response.data === 'string' ? error.response.data : JSON.stringify(error.response.data);
  }
  if (error.response && error.response.status) {
    return `${defaultMessage} (Status: ${error.response.status})`;
  }
  return defaultMessage;
}

const headers = ref([
  { title: 'Type', key: 'type', sortable: true, width: '15%' },
  { title: 'Key / System ID', key: 'details', sortable: false, width: '40%' },
  { title: 'Password', key: 'password', sortable: false, width: '25%' },
  { title: 'Actions', key: 'actions', sortable: false, align: 'end', width: '15%' },
]);

const keyTypes = ref(['admin', 'message', 'smpp']);

const rules = {
  required: value => !!value || 'This field is required.',
  isIdentifierUnique: value => {
    const isDuplicate = apiKeys.value.some((key, index) => {
      if (editedIndex.value !== -1 && index === editedIndex.value) {
        return false;
      }
      return getItemValue(key) === value;
    });
    return !isDuplicate || 'This identifier must be unique across all key types.';
  },
};

const formTitle = computed(() => {
  return editedIndex.value === -1 ? 'New API Key' : `Edit API Key`;
});

function getColorForType(type) {
  switch (type) {
    case 'admin': return 'blue-darken-1';
    case 'message': return 'green-darken-1';
    case 'smpp': return 'orange-darken-1';
    default: return 'grey';
  }
}

function toggleKeyVisibility(item) {
  const itemValue = getItemValue(item);
  revealedKeys.value[itemValue] = !revealedKeys.value[itemValue];
}

async function fetchApiKeys(showSuccessMessage = false) {
  loading.value = true;
  try {
    const adminKey = 'your-admin-api-key';
    const response = await axios.get('/api/admin/api-keys', {
      headers: { 'X-API-Key': adminKey },
    });
    apiKeys.value = Array.isArray(response.data) ? response.data : [];

    const initialVisibility = {};
    apiKeys.value.forEach(key => {
      if (key.type === 'admin' || key.type === 'message') {
        initialVisibility[getItemValue(key)] = false;
      }
    });
    revealedKeys.value = initialVisibility;

    if (showSuccessMessage) {
      showSnackbar('API keys refreshed successfully.', 'success');
    }
  } catch (error) {
    console.error('Failed to fetch API keys:', error);
    showSnackbar(extractErrorMessage(error, 'Failed to fetch API keys.'), 'error');
  } finally {
    loading.value = false;
  }
}

async function updateKeysOnApi(keysToSave, successMessage) {
  loading.value = true;
  try {
    const adminKeyForAuth = 'your-admin-api-key';
    await axios.put('/api/admin/api-keys', keysToSave, {
      headers: {
        'X-API-Key': adminKeyForAuth,
        'Content-Type': 'application/json',
      },
    });
    await fetchApiKeys();
    showSnackbar(successMessage, 'success');
    return true;
  } catch (error) {
    console.error('Failed to update API keys:', error);
    showSnackbar(extractErrorMessage(error, 'Failed to update API keys.'), 'error');
    // On failure, refetch to ensure UI is in sync with the backend state.
    await fetchApiKeys();
    return false;
  } finally {
    loading.value = false;
  }
}


async function save() {
  if (form.value) {
    const { valid } = await form.value.validate();
    if (!valid) {
      showSnackbar('Please fill in all required fields and ensure values are unique.', 'warning');
      return;
    }
  }

  const updatedKeys = JSON.parse(JSON.stringify(apiKeys.value));
  if (editedIndex.value > -1) {
    Object.assign(updatedKeys[editedIndex.value], editedItem.value);
  } else {
    updatedKeys.push(editedItem.value);
  }

  const success = await updateKeysOnApi(updatedKeys, 'API keys updated successfully!');
  if (success) {
    closeDialog();
  }
}

async function deleteItemConfirm() {
  const isAdminKey = itemToDelete.value.type === 'admin';
  const adminKeyCount = apiKeys.value.filter(key => key.type === 'admin').length;

  if (isAdminKey && adminKeyCount <= 1) {
    showSnackbar('Cannot delete the last admin API key.', 'error');
    closeDeleteDialog();
    return;
  }

  const itemValue = getItemValue(itemToDelete.value);
  const updatedKeys = apiKeys.value.filter(key => getItemValue(key) !== itemValue);
  const success = await updateKeysOnApi(updatedKeys, 'API key deleted successfully!');
  if (success) {
    closeDeleteDialog();
  }
}

function addItem() {
  editedIndex.value = -1;
  editedItem.value = { ...defaultItem.value };
  dialog.value = true;
}

function editItem(item) {
  editedIndex.value = apiKeys.value.findIndex(k => getItemValue(k) === getItemValue(item));
  editedItem.value = JSON.parse(JSON.stringify(item));
  dialog.value = true;
}

function deleteItem(item) {
  itemToDelete.value = item;
  deleteDialog.value = true;
}

function closeDialog() {
  dialog.value = false;
  setTimeout(() => {
    editedItem.value = { ...defaultItem.value };
    editedIndex.value = -1;
    if(form.value) form.value.resetValidation();
  }, 300);
}

function closeDeleteDialog() {
  deleteDialog.value = false;
  setTimeout(() => {
    itemToDelete.value = null;
  }, 300);
}

function showSnackbar(text, color = 'info', timeout = 3000) {
  snackbar.value.text = text;
  snackbar.value.color = color;
  snackbar.value.timeout = timeout;
  snackbar.value.show = true;
}

onMounted(() => {
  fetchApiKeys(false);
});
</script>

<style scoped>
td.text-end {
  text-align: end;
}
</style>
