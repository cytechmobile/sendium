import { createRouter, createWebHistory } from 'vue-router';

// Components for routing
import SendSmsForm from './components/SendSmsForm.vue';
import AdvancedRuleEditorPage from './components/AdvancedRuleEditorPage.vue';
import VendorConfigEditorPage from './components/VendorConfigEditorPage.vue';
import VendorCrudPage from './components/VendorCrudPage.vue';
import DlrStatusPage from './components/DlrStatusPage.vue';
import RoutingRuleManagement from './components/RoutingRuleManagement.vue';
import ApiKeyManagementPage from './components/ApiKeyManagementPage.vue'; // Added import

// Placeholder for other pages if they exist or will be added soon
// import HomePage from './components/HomePage.vue';

const routes = [
    {
        path: '/', // Default route
        redirect: '/send-sms' // Redirect to a default page, e.g., SendSms
    },
    {
        path: '/send-sms',
        name: 'SendSms',
        component: SendSmsForm
    },
    {
        path: '/admin/edit-routing-rules-json',
        name: 'AdminRawJsonEditor',
        component: AdvancedRuleEditorPage,
        // meta: { requiresAuth: true, layout: 'admin' } // Example meta fields
    },
    {
        path: '/admin/vendors-config',
        name: 'VendorConfigEditor',
        component: VendorConfigEditorPage,
    },
    {
        path: '/admin/vendors',
        name: 'VendorCRUD',
        component: VendorCrudPage,
    },
    {
        path: '/dlr-status',
        name: 'DlrStatus',
        component: DlrStatusPage
    },
    {
        path: '/admin/routing-rules',
        name: 'RoutingRuleManagement',
        component: RoutingRuleManagement,
    },
    {
      path: '/admin/api-keys',
      name: 'ApiKeyManagement',
      component: ApiKeyManagementPage,
    }
    // Add other routes here
];

const router = createRouter({
    history: createWebHistory(import.meta.env.BASE_URL),
    routes,
});

export default router;
