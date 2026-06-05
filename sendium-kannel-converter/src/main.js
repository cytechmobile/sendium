import 'vuetify/styles';
import './styles.css';

import { createApp } from 'vue';
import { createVuetify } from 'vuetify';
import {
  VAlert,
  VApp,
  VBtn,
  VCard,
  VChip,
  VCol,
  VContainer,
  VDivider,
  VIcon,
  VList,
  VListItem,
  VListItemSubtitle,
  VListItemTitle,
  VMain,
  VRow,
  VSnackbar,
  VTab,
  VTabs,
  VTextarea,
} from 'vuetify/components';
import { aliases, mdi } from 'vuetify/iconsets/mdi-svg';

import App from './App.vue';

const vuetify = createVuetify({
  components: {
    VAlert,
    VApp,
    VBtn,
    VCard,
    VChip,
    VCol,
    VContainer,
    VDivider,
    VIcon,
    VList,
    VListItem,
    VListItemSubtitle,
    VListItemTitle,
    VMain,
    VRow,
    VSnackbar,
    VTab,
    VTabs,
    VTextarea,
  },
  icons: {
    defaultSet: 'mdi',
    aliases,
    sets: { mdi },
  },
  theme: {
    defaultTheme: 'sendiumDark',
    themes: {
      sendiumDark: {
        dark: true,
        colors: {
          background: '#08111f',
          surface: '#101c2f',
          primary: '#ff8a3d',
          secondary: '#58d5c9',
          error: '#ff6678',
          warning: '#ffd166',
          info: '#7aa2ff',
          success: '#79d279',
        },
      },
    },
  },
});

createApp(App).use(vuetify).mount('#app');
