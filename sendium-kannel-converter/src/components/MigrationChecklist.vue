<template>
  <v-card class="guidance-card" elevation="0">
    <p class="panel-kicker">Manual review areas</p>
    <h2>Before running Sendium</h2>
    <ol>
      <li>Review generated secrets and replace placeholders.</li>
      <li>Validate SMPP TON/NPI, bind mode, throughput, and keepalive settings with each provider.</li>
      <li>Translate Kannel SMSC selection rules into `routingTable.conf` manually.</li>
      <li>Move Kannel process, logging, and network settings into deployment configuration where appropriate.</li>
    </ol>
    <div class="reference-links checklist-links">
      <a href="https://github.com/cytechmobile/sendium/blob/main/docs/03-auth-security.md" target="_blank" rel="noreferrer">
        Credentials docs
      </a>
      <a href="https://github.com/cytechmobile/sendium/blob/main/docs/04-smpp-configuration.md" target="_blank" rel="noreferrer">
        SMPP docs
      </a>
      <a href="https://github.com/cytechmobile/sendium/blob/main/docs/05-routing-engine.md" target="_blank" rel="noreferrer">
        Routing docs
      </a>
      <a href="https://www.kannel.org/download/kannel-userguide-snapshot/userguide.html" target="_blank" rel="noreferrer">
        Kannel guide
      </a>
    </div>
  </v-card>
</template>
