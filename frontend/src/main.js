import { createApp } from 'vue';
import App from './App.vue';
import axios from 'axios';
import router from './router'; // Import the router instance

// Vuetify
import 'vuetify/styles'; // Import Vuetify styles
import { createVuetify } from 'vuetify';
import * as components from 'vuetify/components';
import * as directives from 'vuetify/directives';

const vuetify = createVuetify({
  components,
  directives
});



// Axios configuration
// You can set a base URL if your API is on a different host/port and not using Vite proxy
// axios.defaults.baseURL = 'http://localhost:8080/api';
// Or rely on Vite proxy for /api calls

const app = createApp(App);
app.use(vuetify);
app.use(router); // Use the router
// Making axios available globally on app instance (optional, can also import where needed)
// app.config.globalProperties.$axios = axios;

app.mount('#app');
