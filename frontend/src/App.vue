<template>
  <v-app>
    <v-navigation-drawer app v-model="drawer" permanent>
      <v-list-item>
        <v-list-item-content>
          <v-list-item-title class="text-h6">
            Menu
          </v-list-item-title>
        </v-list-item-content>
      </v-list-item>

      <v-divider></v-divider>

      <v-list dense nav>
        <v-list-item
            v-for="item in menuItems"
            :key="item.title"
            :to="item.to"
            :data-testid="item.title"
            link
        >
          <template v-slot:prepend>
            <v-icon>{{ item.icon }}</v-icon>
          </template>
          <v-list-item-title>{{ item.title }}</v-list-item-title>
        </v-list-item>
      </v-list>
    </v-navigation-drawer>

    <v-app-bar app color="primary">
      <v-app-bar-nav-icon @click="drawer = !drawer"></v-app-bar-nav-icon>
      <v-app-bar-title>Sendium</v-app-bar-title>
    </v-app-bar>

    <v-main>
      <v-container fluid>
        <router-view></router-view>
      </v-container>
    </v-main>

    <v-footer app color="primary" class="text-center" style="color: white; justify-content: center;">
      <span>Sendium Frontend &copy; {{ new Date().getFullYear() }}</span>
    </v-footer>
  </v-app>
</template>

<script>
export default {
  data() {
    return {
      drawer: true,
      menuItems: [
        { title: 'Send SMS', icon: 'mdi-send', to: '/send-sms' },
        { title: 'DLR Status', icon: 'mdi-message-check', to: '/dlr-status' },
        { title: 'Vendors', icon: 'mdi-store', to: '/admin/vendors' },
        { title: 'Routing Rules', icon: 'mdi-routes', to: '/admin/routing-rules' },
        { title: 'API Keys', icon: 'mdi-key-variant', to: '/admin/api-keys' },
        { title: 'Advanced Rules (JSON)', icon: 'mdi-code-json', to: '/admin/edit-routing-rules-json' },
        { title: 'Vendors (JSON)', icon: 'mdi-code-json', to: '/admin/vendors-config' },
      ],
    };
  },
};
</script>

<style>
</style>
