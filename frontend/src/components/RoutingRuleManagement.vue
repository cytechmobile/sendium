<template>
  <v-container>
    <v-toolbar color="lighten-2" class="mb-4">
      <v-toolbar-title>Routing Rule Management</v-toolbar-title>
      <v-spacer></v-spacer>
      <v-btn color="secondary" class="mr-2" @click="openAddGroupDialog" data-testid="add-group-btn">Add New Group</v-btn>
      <v-btn color="primary" class="mr-2" :disabled="!isChanged" @click="saveChanges" data-testid="save-changes-btn">Save Changes</v-btn>
      <v-btn color="grey" :disabled="!isChanged" @click="undoChangesDialog = true" data-testid="undo-changes-btn">Undo Changes</v-btn>
    </v-toolbar>

    <v-expansion-panels v-if="!isLoading && Object.keys(currentRules).length" variant="accordion" class="mb-6" data-testid="rules-accordion">
      <v-expansion-panel
          v-for="(group, groupName) in currentRules"
          :key="groupName"
          :data-testid="`group-panel-${groupName}`"
      >
        <v-expansion-panel-title>
          <template v-slot:default="{ expanded }">
            <v-row no-gutters class="align-center">
              <v-col cols="8" sm="10" md="10">
                <strong>{{ groupName }}</strong>
              </v-col>
              <v-col cols="4" sm="2" md="2" class="text-right">
                <v-btn icon="mdi-plus" size="small" variant="text" @click.stop="openAddRuleDialog(groupName)" title="Add Rule to Group" :data-testid="`add-rule-to-group-btn-${groupName}`"></v-btn>
                <v-btn icon="mdi-pencil" size="small" variant="text" @click.stop="openEditGroupDialog(groupName)" title="Edit Group" :data-testid="`edit-group-btn-${groupName}`"></v-btn>
                <v-btn
                    icon="mdi-delete"
                    size="small"
                    variant="text"
                    :disabled="groupName === 'default'"
                    @click.stop="openDeleteGroupDialog(groupName)"
                    title="Delete Group"
                    :data-testid="`delete-group-btn-${groupName}`"
                ></v-btn>
              </v-col>
            </v-row>
          </template>
        </v-expansion-panel-title>
        <v-expansion-panel-text>
          <v-list v-if="group.length > 0" dense>
            <v-list-item
                v-for="(rule, ruleIndex) in group"
                :key="rule.ruleName"
                class="mb-2 elevation-1"
                :data-testid="`rule-item-${groupName}-${rule.ruleName}`"
            >
              <v-row align="center" no-gutters>
                <v-col cols="12" md="8">
                  <v-list-item-title class="font-weight-bold">{{ rule.ruleName }}</v-list-item-title>
                  <v-list-item-subtitle class="mt-1">
                    <strong>IF:</strong>
                    <template v-if="Object.keys(rule?.conditions || {}).length > 0">
                      <span v-if="rule.conditions?.sender" class="ml-2">Sender matches: <code>{{ rule.conditions?.sender }}</code></span>
                      <span v-if="rule.conditions?.recipient" class="ml-2">Recipient matches: <code>{{ rule.conditions?.recipient }}</code></span>
                      <span v-if="rule.conditions?.textContains" class="ml-2">Text contains: <code>{{ rule.conditions?.textContains }}</code></span>
                      <span v-if="rule.conditions?.textMatchesRegex" class="ml-2">Text matches regex: <code>{{ rule.conditions?.textMatchesRegex }}</code></span>
                    </template>
                    <span v-else class="ml-2">(No conditions - always matches)</span>
                  </v-list-item-subtitle>
                  <v-list-item-subtitle class="mt-1">
                    <strong>THEN:</strong>
                    <span v-if="rule.destinationId" class="ml-2">Send to Destination: <code>{{ rule.destinationId }}</code></span>
                    <span v-else-if="rule.nextRuleGroupName" class="ml-2">Chain to Group: <code>{{ rule.nextRuleGroupName }}</code></span>
                    <span v-else class="ml-2 text-grey-darken-1">(No action defined)</span>
                  </v-list-item-subtitle>
                </v-col>

                <v-col cols="12" md="4" class="text-md-right mt-2 mt-md-0">
                  <v-btn icon="mdi-arrow-up" size="x-small" variant="text" :disabled="ruleIndex === 0" @click="moveRule(groupName, ruleIndex, -1)" title="Move Up" :id="`move-rule-up-btn-${groupName}-${rule.ruleName}`"></v-btn>
                  <v-btn icon="mdi-arrow-down" size="x-small" variant="text" :disabled="ruleIndex === group.length - 1" @click="moveRule(groupName, ruleIndex, 1)" title="Move Down" :data-testid="`move-rule-down-btn-${groupName}-${rule.ruleName}`"></v-btn>
                  <v-btn icon="mdi-pencil" size="x-small" variant="text" @click="openEditRuleDialog(groupName, ruleIndex)" title="Edit Rule" :data-testid="`edit-rule-btn-${groupName}-${rule.ruleName}`"></v-btn>
                  <v-btn icon="mdi-delete" size="x-small" variant="text" @click="openDeleteRuleDialog(groupName, ruleIndex)" title="Delete Rule" :data-testid="`delete-rule-btn-${groupName}-${rule.ruleName}`"></v-btn>
                </v-col>
              </v-row>
            </v-list-item>
          </v-list>
          <p v-else class="text-grey pa-2">No rules in this group. Click the <v-icon small>mdi-plus</v-icon> button on the group header to add one.</p>
        </v-expansion-panel-text>
      </v-expansion-panel>
    </v-expansion-panels>
    <v-alert v-else-if="isLoading" type="info" prominent data-testid="loading-alert">
      Loading routing rules...
    </v-alert>
    <v-alert v-else type="warning" prominent data-testid="no-rules-alert">
      No routing rule groups found or unable to load configuration.
    </v-alert>

    <!-- Undo Confirmation Dialog -->
    <v-dialog v-model="undoChangesDialog" max-width="500px">
      <v-card data-testid="undo-dialog">
        <v-card-title class="headline">Confirm Undo</v-card-title>
        <v-card-text>
          Are you sure you want to undo all changes? This action cannot be reversed.
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn color="blue darken-1" text @click="undoChangesDialog = false" data-testid="undo-dialog-cancel-btn">Cancel</v-btn>
          <v-btn color="red darken-1" text @click="confirmUndoChanges" data-testid="undo-dialog-confirm-btn">Undo</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Add/Edit Group Dialog -->
    <v-dialog v-model="groupDialog" max-width="500px" persistent>
      <v-card data-testid="group-dialog">
        <v-card-title>
          <span class="headline">{{ editingGroup.isNew ? 'Add New Group' : 'Edit Group Name' }}</span>
        </v-card-title>
        <v-card-text>
          <v-text-field
              v-model="editingGroup.name"
              label="Group Name"
              required
              :error-messages="groupNameError"
              @input="groupNameError = ''"
              @keyup.enter="saveGroup"
              :id="'group-name-input'"
          ></v-text-field>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn color="blue darken-1" text @click="closeGroupDialog" data-testid="group-dialog-cancel-btn">Cancel</v-btn>
          <v-btn color="blue darken-1" :disabled="!editingGroup.name.trim()" @click="saveGroup" data-testid="group-dialog-save-btn">Save</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Delete Group Confirmation Dialog -->
    <v-dialog v-model="deleteGroupConfirmDialog" max-width="500px">
      <v-card data-testid="delete-group-dialog">
        <v-card-title class="headline">Confirm Delete Group</v-card-title>
        <v-card-text>
          Are you sure you want to delete the group "<strong>{{ currentGroupName }}</strong>"? This will also delete all rules within this group. This action cannot be undone. Any rules chaining to this group will be modified.
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn color="blue darken-1" text @click="deleteGroupConfirmDialog = false" data-testid="delete-group-dialog-cancel-btn">Cancel</v-btn>
          <v-btn color="red darken-1" text @click="confirmDeleteGroup" data-testid="delete-group-dialog-confirm-btn">Delete</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Add/Edit Rule Dialog -->
    <v-dialog v-model="ruleDialog" max-width="700px" persistent>
      <v-card data-testid="rule-dialog">
        <v-card-title>
          <span class="headline">{{ editingRuleIsNew ? 'Add New Rule' : 'Edit Rule' }} in Group: <strong>{{ currentGroupName }}</strong></span>
        </v-card-title>
        <v-card-text>
          <v-text-field
              v-model="editingRule.ruleName"
              label="Rule Name (unique within group)"
              required
              :error-messages="ruleFormError && ruleFormError.ruleName ? ruleFormError.ruleName : ''"
              @input="() => { if (ruleFormError && ruleFormError.ruleName) ruleFormError.ruleName = ''; }"
              :id="'rule-name-input'"
          ></v-text-field>

            <v-divider class="my-3"></v-divider>
            <p class="subtitle-1">Conditions (Optional - leave blank if not needed)</p>
            <v-text-field v-model="editingRule.conditions.sender" label="Sender (Regex, e.g., +1800.*)" data-testid="rule-sender-input"></v-text-field>
            <v-text-field v-model="editingRule.conditions.recipient" label="Recipient (Regex, e.g., 12345)" data-testid="rule-recipient-input"></v-text-field>
            <v-text-field v-model="editingRule.conditions.textContains" label="Text Contains (Simple text match)" :id="'rule-text-contains-input'"></v-text-field>
            <v-text-field v-model="editingRule.conditions.textMatchesRegex" label="Text Matches (Regex)" data-testid="rule-text-matches-regex-input"></v-text-field>

            <v-divider class="my-3"></v-divider>
            <p class="subtitle-1">Action</p>
            <v-radio-group v-model="editingRule.actionType" inline data-testid="rule-action-type-radio-group">
              <v-radio label="Send to Destination" value="send" data-testid="rule-action-send-radio"></v-radio>
              <v-radio label="Chain to Group" value="chain" data-testid="rule-action-chain-radio"></v-radio>
            </v-radio-group>

            <v-text-field
                v-if="editingRule.actionType === 'send'"
                v-model="editingRule.destinationId"
                label="Destination ID"
                required
                :error-messages="ruleFormError && ruleFormError.destinationId ? ruleFormError.destinationId : ''"
                @input="() => { if (ruleFormError && ruleFormError.destinationId) ruleFormError.destinationId = ''; }"
                :id="'rule-destination-id-input'"
            ></v-text-field>

            <v-select
                v-if="editingRule.actionType === 'chain'"
                v-model="editingRule.nextRuleGroupName"
                :items="Object.keys(currentRules).filter(name => name !== currentGroupName)"
                label="Target Group Name"
                required
                :error-messages="ruleFormError && ruleFormError.nextRuleGroupName ? ruleFormError.nextRuleGroupName : ''"
                @input="() => { if (ruleFormError && ruleFormError.nextRuleGroupName) ruleFormError.nextRuleGroupName = ''; }"
                no-data-text="No other groups available"
                data-testid="rule-chain-group-select"
            ></v-select>
            <div v-if="ruleFormError && typeof ruleFormError === 'string'" class="text-red text-caption mt-2">{{ ruleFormError }}</div>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn color="grey" text @click="closeRuleDialog" data-testid="rule-dialog-cancel-btn">Cancel</v-btn>
          <v-btn color="primary" @click="saveRule" data-testid="rule-dialog-save-btn">Save Rule</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Delete Rule Confirmation Dialog -->
    <v-dialog v-model="deleteRuleConfirmDialog" max-width="500px">
      <v-card data-testid="delete-rule-dialog">
        <v-card-title class="headline">Confirm Delete Rule</v-card-title>
        <v-card-text>
          Are you sure you want to delete the rule "<strong>{{ currentRuleIndex !== null ? currentRules[currentGroupName]?.[currentRuleIndex]?.ruleName : '' }}</strong>" from group "<strong>{{ currentGroupName }}</strong>"? This action cannot be undone.
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn color="blue darken-1" text @click="deleteRuleConfirmDialog = false" data-testid="delete-rule-dialog-cancel-btn">Cancel</v-btn>
          <v-btn color="red darken-1" text @click="confirmDeleteRule" data-testid="delete-rule-dialog-confirm-btn">Delete</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-snackbar v-model="snackbar.show" :color="snackbar.color" :timeout="snackbar.timeout" data-testid="snackbar">
      {{ snackbar.text }}
      <template v-slot:actions>
        <v-btn color="white" text @click="snackbar.show = false">Close</v-btn>
      </template>
    </v-snackbar>
  </v-container>
</template>

<script setup>
// --- SCRIPT SECTION IS UNCHANGED ---
import { ref, reactive, onMounted, computed } from 'vue';
import axios from 'axios';
import { cloneDeep } from 'lodash-es';

// State
const currentRules = reactive({});
const originalRules = ref({});
const isLoading = ref(false);
const undoChangesDialog = ref(false);

// Dialog states
const groupDialog = ref(false);
const deleteGroupConfirmDialog = ref(false);

const editingGroup = reactive({
  name: '',
  originalName: '',
  isNew: true,
});
const groupNameError = ref('');
const currentGroupName = ref('');

// Dialog states for rules
const ruleDialog = ref(false);
const deleteRuleConfirmDialog = ref(false);

const initialRuleData = () => ({
  ruleName: '',
  destinationId: '',
  nextRuleGroupName: '',
  conditions: {
    sender: '',
    recipient: '',
    textContains: '',
    textMatchesRegex: '',
  },
  actionType: 'send',
});

const editingRule = reactive(initialRuleData());
const editingRuleIsNew = ref(true);
const ruleFormError = ref('');
const currentRuleIndex = ref(null);

const snackbar = reactive({
  show: false,
  text: '',
  color: '',
  timeout: 3000,
});

const isChanged = computed(() => {
  return JSON.stringify(currentRules) !== JSON.stringify(originalRules.value);
});

const fetchRoutingRules = async () => {
  isLoading.value = true;
  let response;
  const apiKey = 'your-admin-api-key';
  const config = { headers: { 'X-API-Key': apiKey } };
  try {
    response = await axios.get('/api/admin/routing-rules', config);
    let parsedRules = typeof response.data === 'string' ? JSON.parse(response.data) : response.data;
    Object.keys(currentRules).forEach(key => delete currentRules[key]); // Clear existing
    Object.assign(currentRules, parsedRules); // Assign new
    originalRules.value = cloneDeep(parsedRules);
    showSnackbar('Routing rules loaded successfully.', 'success');
  } catch (error) {
    console.error('Error fetching routing rules:', error);
    let errorMessage = 'Failed to load routing rules.';
    if (error instanceof SyntaxError) {
      errorMessage = 'Failed to parse routing rules: Invalid format received.';
    } else if (error.response) {
      errorMessage = `Failed to load routing rules: Server responded with ${error.response.status}.`;
    }
    showSnackbar(errorMessage, 'error');
    Object.assign(currentRules, { default: [] });
    originalRules.value = cloneDeep({ default: [] });
  } finally {
    isLoading.value = false;
  }
};

const saveChanges = async () => {
  isLoading.value = true;
  try {
    const apiKey = 'your-admin-api-key';
    await axios.put('/api/admin/routing-rules', currentRules, {
      headers: { 'Content-Type': 'application/json', 'X-API-Key': apiKey },
    });
    originalRules.value = cloneDeep(currentRules);
    showSnackbar('Routing rules saved successfully.', 'success');
  } catch (error) {
    console.error('Error saving routing rules:', error);
    showSnackbar('Failed to save routing rules. Please try again.', 'error');
  } finally {
    isLoading.value = false;
  }
};

const confirmUndoChanges = () => {
  Object.keys(currentRules).forEach(key => delete currentRules[key]);
  Object.assign(currentRules, cloneDeep(originalRules.value));
  undoChangesDialog.value = false;
  showSnackbar('Changes have been undone.', 'info');
};

const showSnackbar = (text, color = 'info', timeout = 3000) => {
  snackbar.text = text;
  snackbar.color = color;
  snackbar.timeout = timeout;
  snackbar.show = true;
};

onMounted(() => { fetchRoutingRules(); });

const openAddGroupDialog = () => {
  editingGroup.isNew = true;
  editingGroup.name = '';
  editingGroup.originalName = '';
  groupNameError.value = '';
  groupDialog.value = true;
};

const openEditGroupDialog = (groupName) => {
  editingGroup.isNew = false;
  editingGroup.name = groupName;
  editingGroup.originalName = groupName;
  groupNameError.value = '';
  groupDialog.value = true;
};

const closeGroupDialog = () => {
  groupDialog.value = false;
};

const saveGroup = () => {
  const newName = editingGroup.name.trim();
  if (!newName) {
    groupNameError.value = 'Group name cannot be empty.';
    return;
  }
  if (editingGroup.isNew) {
    if (currentRules.hasOwnProperty(newName)) {
      groupNameError.value = 'Group name already exists.';
      return;
    }
    currentRules[newName] = [];
    showSnackbar(`Group "${newName}" created successfully.`, 'success');
  } else {
    if (newName !== editingGroup.originalName && currentRules.hasOwnProperty(newName)) {
      groupNameError.value = 'Group name already exists.';
      return;
    }
    if (editingGroup.originalName === 'default' && newName !== 'default') {
      groupNameError.value = 'Cannot rename the "default" group.';
      return;
    }
    if (newName !== editingGroup.originalName) {
      currentRules[newName] = cloneDeep(currentRules[editingGroup.originalName]);
      delete currentRules[editingGroup.originalName];
      Object.values(currentRules).flat().forEach(rule => {
        if (rule.nextRuleGroupName === editingGroup.originalName) {
          rule.nextRuleGroupName = newName;
        }
      });
      showSnackbar(`Group "${editingGroup.originalName}" renamed to "${newName}".`, 'success');
    }
  }
  closeGroupDialog();
};

const openDeleteGroupDialog = (groupName) => {
  if (groupName === 'default') {
    showSnackbar('The "default" group cannot be deleted.', 'warning');
    return;
  }
  currentGroupName.value = groupName;
  deleteGroupConfirmDialog.value = true;
};

const confirmDeleteGroup = () => {
  const groupNameToDelete = currentGroupName.value;
  delete currentRules[groupNameToDelete];
  Object.values(currentRules).flat().forEach(rule => {
    if (rule.nextRuleGroupName === groupNameToDelete) {
      rule.nextRuleGroupName = 'default';
    }
  });
  showSnackbar(`Group "${groupNameToDelete}" deleted successfully.`, 'success');
  deleteGroupConfirmDialog.value = false;
  currentGroupName.value = '';
};

const openAddRuleDialog = (groupName) => {
  Object.assign(editingRule, initialRuleData());
  editingRuleIsNew.value = true;
  currentGroupName.value = groupName;
  ruleDialog.value = true;
};

const openEditRuleDialog = (groupName, ruleIndex) => {
  const ruleToEdit = currentRules[groupName][ruleIndex];
  Object.assign(editingRule, { ...initialRuleData(), ...cloneDeep(ruleToEdit) });
  editingRule.actionType = ruleToEdit.destinationId ? 'send' : 'chain';
  editingRuleIsNew.value = false;
  currentGroupName.value = groupName;
  currentRuleIndex.value = ruleIndex;
  ruleDialog.value = true;
};

const closeRuleDialog = () => {
  ruleDialog.value = false;
};

const saveRule = () => {
  ruleFormError.value = '';
  const errors = {};
  const newRuleName = editingRule.ruleName.trim();
  if (!newRuleName) errors.ruleName = 'Rule name is required.';
  if (currentRules[currentGroupName.value].some((r, i) => r.ruleName === newRuleName && (editingRuleIsNew.value || i !== currentRuleIndex.value))) {
    errors.ruleName = 'Rule name must be unique within this group.';
  }
  if (editingRule.actionType === 'send' && !editingRule.destinationId?.trim()) {
    errors.destinationId = 'Destination ID is required.';
  }
  if (editingRule.actionType === 'chain' && !editingRule.nextRuleGroupName) {
    errors.nextRuleGroupName = 'Target group is required.';
  }
  if (Object.keys(errors).length > 0) {
    ruleFormError.value = errors;
    return;
  }

  const ruleToSave = {
    ruleName: newRuleName,
    conditions: Object.fromEntries(Object.entries(editingRule.conditions).filter(([, val]) => val?.trim())),
    destinationId: editingRule.actionType === 'send' ? editingRule.destinationId.trim() : '',
    nextRuleGroupName: editingRule.actionType === 'chain' ? editingRule.nextRuleGroupName : '',
  };

  if (editingRuleIsNew.value) {
    currentRules[currentGroupName.value].push(ruleToSave);
  } else {
    currentRules[currentGroupName.value][currentRuleIndex.value] = ruleToSave;
  }
  showSnackbar(`Rule "${ruleToSave.ruleName}" saved.`, 'success');
  closeRuleDialog();
};

const openDeleteRuleDialog = (groupName, ruleIndex) => {
  currentGroupName.value = groupName;
  currentRuleIndex.value = ruleIndex;
  deleteRuleConfirmDialog.value = true;
};

const confirmDeleteRule = () => {
  currentRules[currentGroupName.value].splice(currentRuleIndex.value, 1);
  showSnackbar('Rule deleted.', 'success');
  deleteRuleConfirmDialog.value = false;
};

const moveRule = (groupName, ruleIndex, direction) => {
  const rules = currentRules[groupName];
  const [ruleToMove] = rules.splice(ruleIndex, 1);
  rules.splice(ruleIndex + direction, 0, ruleToMove);
  showSnackbar('Rule moved. Remember to save changes.', 'info', 1500);
};

</script>

<style scoped>
.v-toolbar-title {
  font-weight: bold;
}
</style>
