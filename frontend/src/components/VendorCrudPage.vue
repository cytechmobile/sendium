<template>
  <v-container>
    <v-card>
      <v-card-title>
        Vendors
        <v-spacer></v-spacer>
        <v-btn color="primary" @click="openCreateDialog" data-testid="new-vendor-btn">New Vendor</v-btn>
      </v-card-title>
      <v-data-table
          :headers="headers"
          :items="vendors"
          :loading="loading"
          class="elevation-1"
          data-testid="vendors-table"
          :item-key="'id'"
      >
        <template v-slot:item="{ item }">
          <tr :data-testid="`vendor-row-${item.id}`">
            <td>{{ item.id }}</td>
            <td>{{ item.type }}</td>
            <td>{{ item.host }}</td>
            <td>{{ item.port }}</td>
            <td><span v-if="item.type === 'SMPP'">{{ item.systemId }}</span></td>
            <td><span v-if="item.type === 'HTTP'">{{ item.httpApiKey ? '****' : '' }}</span></td>
            <td>{{ item.httpApiUrl }}</td>
            <td>{{ item.transactionsPerSecond }}</td>
            <td class="text-center">
              <v-icon v-if="item.enabled" color="success" data-testid="enabled-icon">mdi-check-circle</v-icon>
              <v-icon v-else color="error" data-testid="disabled-icon">mdi-close-circle</v-icon>
            </td>
            <td>
              <v-btn size="small" icon="mdi-pencil" class="mr-2" @click="editVendor(item)" :data-testid="`edit-vendor-btn-${item.id}`"></v-btn>
              <v-btn size="small" icon="mdi-delete" @click="confirmDeleteVendor(item)" :data-testid="`delete-vendor-btn-${item.id}`"/>
            </td>
          </tr>
        </template>
      </v-data-table>
    </v-card>

    <!-- Create/Edit Dialog -->
    <v-dialog v-model="showEditDialog" max-width="600px" persistent>
      <v-card data-testid="edit-dialog">
        <v-card-title>
          <span class="headline">{{ dialogTitle }}</span>
        </v-card-title>
        <v-card-text>
          <v-form ref="form" v-model="validForm">
            <v-text-field
                v-model="editedItem.id"
                label="ID"
                :rules="[v => !!v || 'ID is required']"
                required
                :disabled="isEditing"
                :id="'vendor-id-input'"
            ></v-text-field>
            <v-select
                v-model="editedItem.type"
                :items="vendorTypes"
                label="Type"
                :rules="[v => !!v || 'Type is required']"
                required
                :disabled="isEditing"
                @update:modelValue="onVendorTypeChange"
                data-testid="vendor-type-select"
            ></v-select>

            <!-- SMPP Specific Fields -->
            <template v-if="editedItem.type === 'SMPP'">
              <v-text-field
                  v-model="editedItem.host"
                  label="Host"
                  :rules="[v => !!v || 'Host is required']"
                  required
                  :id="'smpp-host-input'"
              ></v-text-field>
              <v-text-field
                  v-model.number="editedItem.port"
                  label="Port"
                  type="number"
                  :rules="[v => !!v || 'Port is required', v => (Number.isInteger(Number(v)) && Number(v) > 0) || 'Port must be a positive integer']"
                  required
                  :id="'smpp-port-input'"
              ></v-text-field>
              <v-text-field
                  v-model="editedItem.systemId"
                  label="System ID"
                  :rules="[v => !!v || 'System ID is required']"
                  required
                  :id="'smpp-system-id-input'"
              ></v-text-field>
              <v-text-field
                  v-model="editedItem.password"
                  label="Password"
                  type="password"
                  :rules="[v => !!v || 'Password is required']"
                  required
                  :id="'smpp-password-input'"
              ></v-text-field>
              <v-text-field
                  v-model.number="editedItem.reconnectIntervalSeconds"
                  label="Reconnect Interval (seconds)"
                  type="number"
                  :rules="[v => v === null || v === '' || (Number.isInteger(Number(v)) && Number(v) >= 0) || 'Must be a non-negative integer']"
                  :id="'smpp-reconnect-interval-input'"
              ></v-text-field>
              <v-text-field
                  v-model.number="editedItem.enquireLinkIntervalSeconds"
                  label="Enquire Link Interval (seconds)"
                  type="number"
                  :rules="[v => v === null || v === '' || (Number.isInteger(Number(v)) && Number(v) >= 0) || 'Must be a non-negative integer']"
                  :id="'smpp-enquire-link-interval-input'"
              ></v-text-field>
            </template>

            <!-- HTTP Specific Fields -->
            <template v-if="editedItem.type === 'HTTP'">
              <v-text-field
                  v-model="editedItem.httpApiKey"
                  label="API Key"
                  :rules="[v => !!v || 'API Key is required']"
                  required
                  :id="'http-api-key-input'"
              ></v-text-field>
              <v-text-field
                  v-model="editedItem.httpApiUrl"
                  label="API URL"
                  :rules="[v => !!v || 'API URL is required']"
                  required
                  :id="'http-api-url-input'"
              ></v-text-field>
            </template>

            <v-text-field
                v-model.number="editedItem.transactionsPerSecond"
                label="Transactions Per Second"
                type="number"
                step="0.1"
                :rules="[v => v === null || v === '' || (Number(v) > 0) || 'Must be a positive number']"
                :id="'vendor-tps-input'"
            ></v-text-field>
            <v-switch
                v-model="editedItem.enabled"
                label="Enabled"
                :id="'vendor-enabled-switch'"
            ></v-switch>
          </v-form>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn color="blue darken-1" text @click="closeEditDialog" data-testid="dialog-cancel-btn">Cancel</v-btn>
          <v-btn color="blue darken-1" text @click="saveVendor" :disabled="!validForm" data-testid="dialog-save-btn">Save</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Delete Confirmation Dialog -->
    <v-dialog v-model="showDeleteDialog" max-width="500px">
      <v-card data-testid="delete-dialog">
        <v-card-title class="headline">Confirm Delete</v-card-title>
          <v-card-text>Are you sure you want to delete this vendor? This action cannot be undone.</v-card-text>
          <v-card-actions>
            <v-spacer></v-spacer>
            <v-btn color="blue darken-1" text @click="closeDeleteDialog" data-testid="delete-dialog-cancel-btn">Cancel</v-btn>
            <v-btn color="red darken-1" text @click="executeDelete" data-testid="delete-dialog-confirm-btn">Delete</v-btn>
          </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Snackbar for notifications -->
    <v-snackbar v-model="snackbar" :color="snackbarColor" :timeout="3000" top right data-testid="snackbar">
      {{ snackbarText }}
      <template v-slot:action="{ attrs }">
        <v-btn color="white" text v-bind="attrs" @click="snackbar = false">Close</v-btn>
      </template>
    </v-snackbar>
  </v-container>
</template>

<script>
// --- SCRIPT SECTION IS UNCHANGED ---
import axios from 'axios';

export default {
  name: 'SmppVendorCrudPage',
  data() {
    return {
      loading: false,
      vendors: [],
      vendorTypes: ['SMPP', 'HTTP'],
      headers: [
        { title: 'ID', value: 'id' },
        { title: 'Type', value: 'type' },
        { title: 'Host', value: 'host' },
        { title: 'Port', value: 'port' },
        { title: 'System ID', value: 'systemId' },
        { title: 'API Key', value: 'httpApiKey' },
        { title: 'url', value: 'httpApiUrl' },
        { title: 'TPS', value: 'transactionsPerSecond' },
        { title: 'Enabled', value: 'enabled', sortable: false, align: 'center' },
        { title: 'Actions', value: 'actions', sortable: false },
      ],
      editedItem: {
        id: '',
        type: 'SMPP',
        host: '',
        port: null,
        enabled: true,
        transactionsPerSecond: null,
        systemId: '',
        password: '',
        reconnectIntervalSeconds: null,
        enquireLinkIntervalSeconds: null,
        httpApiKey: '',
        httpApiUrl: ''
      },
      commonDefaultItem: {
        id: '',
        enabled: true,
        transactionsPerSecond: 10.0,
      },
      defaultSmppSpecificItem: {
        type: 'SMPP',
        host: '',
        port: null,
        systemId: '',
        password: '',
        reconnectIntervalSeconds: 30,
        enquireLinkIntervalSeconds: 60,
      },
      defaultHttpSpecificItem: {
        type: 'HTTP',
        httpApiKey: '',
        httpApiUrl: ''
      },
      dialogTitle: '',
      isEditing: false,
      validForm: true,
      showEditDialog: false,
      snackbar: false,
      snackbarText: '',
      snackbarColor: '',
      showDeleteDialog: false,
      itemToDelete: null,
    };
  },
  methods: {
    async fetchVendors() {
      this.loading = true;
      try {
        const apiKey = 'your-admin-api-key';
        const response = await axios.get('/api/admin/vendors', {
          headers: { 'X-API-Key': apiKey },
        });
        this.vendors = response.data;
      } catch (error) {
        console.error('Error fetching vendors:', error);
        this.showSnackbar('Error fetching vendors', 'error');
      } finally {
        this.loading = false;
      }
    },
    resetEditedItem(vendorType) {
      const common = { ...this.commonDefaultItem, id: this.editedItem.id };
      if (vendorType === 'SMPP') {
        this.editedItem = { ...common, ...this.defaultSmppSpecificItem, type: 'SMPP' };
      } else if (vendorType === 'HTTP') {
        this.editedItem = { ...common, ...this.defaultHttpSpecificItem, type: 'HTTP' };
      } else {
        this.editedItem = { ...common, ...this.defaultSmppSpecificItem, type: 'SMPP' };
      }
    },
    onVendorTypeChange(newType) {
      if (!this.isEditing) {
        const currentId = this.editedItem.id;
        this.resetEditedItem(newType);
        this.editedItem.id = currentId;
        this.$nextTick(() => { if (this.$refs.form) this.$refs.form.resetValidation(); });
      }
    },
    openCreateDialog() {
      this.isEditing = false;
      this.dialogTitle = 'New Vendor';
      this.editedItem = { ...this.commonDefaultItem, ...this.defaultSmppSpecificItem, id: '', type: 'SMPP' };
      this.showEditDialog = true;
      this.$nextTick(() => { if (this.$refs.form) this.$refs.form.resetValidation(); });
    },
    editVendor(item) {
      this.isEditing = true;
      this.dialogTitle = `Edit ${item.type} Vendor`;
      this.editedItem = JSON.parse(JSON.stringify(item));
      if (this.editedItem.type === 'SMPP') {
        this.editedItem = { ...this.commonDefaultItem, ...this.defaultSmppSpecificItem, ...this.editedItem };
      } else if (this.editedItem.type === 'HTTP') {
        this.editedItem = { ...this.commonDefaultItem, ...this.defaultHttpSpecificItem, ...this.editedItem };
      }
      this.showEditDialog = true;
      this.$nextTick(() => { if (this.$refs.form) this.$refs.form.resetValidation(); });
    },
    closeEditDialog() {
      this.showEditDialog = false;
      this.$nextTick(() => {
        this.editedItem = { ...this.commonDefaultItem, ...this.defaultSmppSpecificItem, type: 'SMPP', id: ''};
        this.isEditing = false;
        if (this.$refs.form) this.$refs.form.resetValidation();
      });
    },
    async saveVendor() {
      if (this.$refs.form.validate()) {
        this.loading = true;
        const apiKey = 'your-admin-api-key';
        const config = { headers: { 'X-API-Key': apiKey } };
        let payload = { ...this.editedItem };
        if (payload.type === 'SMPP') {
          delete payload.httpApiKey;
          delete payload.httpApiUrl;
        } else if (payload.type === 'HTTP') {
          delete payload.systemId;
          delete payload.password;
          delete payload.reconnectIntervalSeconds;
          delete payload.enquireLinkIntervalSeconds;
        }
        try {
          if (this.isEditing) {
            await axios.put(`/api/admin/vendors/${payload.id}`, payload, config);
            this.showSnackbar('Vendor updated successfully', 'success');
          } else {
            await axios.post('/api/admin/vendors', payload, config);
            this.showSnackbar('Vendor created successfully', 'success');
          }
          this.closeEditDialog();
          await this.fetchVendors();
        } catch (error) {
          console.error('Error saving vendor:', error);
          this.showSnackbar(`Error saving vendor: ${error.response?.data?.message || error.message}`, 'error');
        } finally {
          this.loading = false;
        }
      }
    },
    confirmDeleteVendor(item) {
      this.itemToDelete = item;
      this.showDeleteDialog = true;
    },
    closeDeleteDialog() {
      this.showDeleteDialog = false;
      this.itemToDelete = null;
    },
    async executeDelete() {
      if (!this.itemToDelete) return;
      this.loading = true;
      const apiKey = 'your-admin-api-key';
      const config = { headers: { 'X-API-Key': apiKey } };
      try {
        await axios.delete(`/api/admin/vendors/${this.itemToDelete.id}`, config);
        this.showSnackbar('Vendor deleted successfully', 'success');
        await this.fetchVendors();
      } catch (error) {
        console.error('Error deleting vendor:', error);
        this.showSnackbar(`Error deleting vendor: ${error.response?.data?.message || error.message}`, 'error');
      } finally {
        this.loading = false;
        this.closeDeleteDialog();
      }
    },
    showSnackbar(text, color) {
      this.snackbarText = text;
      this.snackbarColor = color;
      this.snackbar = true;
    }
  },
  mounted() {
    this.fetchVendors();
  },
};
</script>

<style scoped>
/* Minor fix: Vuetify 3 uses text-align, not align property for table headers */
.v-data-table-header th.text-center {
  text-align: center;
}
</style>
