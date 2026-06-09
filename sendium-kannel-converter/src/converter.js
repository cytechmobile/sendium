const SAMPLE_KANNEL_CONFIG = `# Minimal Kannel-style example
group = core
admin-port = 13000
smsbox-port = 13001

group = sendsms-user
username = legacy-http-user
password = change-me
user-allow-ip = 10.0.0.15

group = smsc
smsc = smpp
smsc-id = upstream-a
host = smpp.provider.example
port = 2775
smsc-username = upstream-system-id
smsc-password = upstream-password
transceiver-mode = true
throughput = 50
system-type = VMA
enquire-link-interval = 30
interface-version = 34
source-addr-ton = 5
source-addr-npi = 0
dest-addr-ton = 1
dest-addr-npi = 1
address-range = ""
allowed-smsc-id = upstream-a
`;

const SAMPLE_SMPPBOX_CONFIG = `# Optional smppbox / opensmppbox-style ingress example
group = smppbox
id = smpp-ingress
smppbox-port = 2775
system-id = downstream-user
password = downstream-password
max-connections = 100
max-connections-per-ip = 4
throughput = 20
max-pending-submits = 1000
`;

const KEY_CATALOG = {
  smsc: {
    mapped: new Set([
      'smsc',
      'smsc-id',
      'id',
      'host',
      'port',
      'smsc-username',
      'username',
      'smsc-password',
      'password',
      'system-type',
      'transceiver-mode',
      'throughput',
      'enquire-link-interval',
      'keepalive',
      'reconnect-delay',
      'interface-version',
      'source-addr-ton',
      'source-addr-npi',
      'dest-addr-ton',
      'dest-addr-npi',
      'address-range',
    ]),
    manual: new Map([
      ['allowed-smsc-id', 'Kannel routing constraints must be reviewed against Sendium `routingTable.conf`.'],
      ['preferred-smsc-id', 'Kannel routing preference must be reviewed against Sendium `routingTable.conf`.'],
      ['denied-smsc-id', 'Kannel SMSC deny routing has no direct beta converter mapping.'],
      ['alt-charset', 'Provider charset behavior needs manual review against Sendium DCS charset mappings.'],
      ['log-file', 'Logging paths are runtime/deployment configuration in Sendium and are not worker mappings.'],
      ['log-level', 'Logging levels are runtime/deployment configuration in Sendium and are not worker mappings.'],
      ['max-pending-submits', 'Sendium SMPP client window behavior must be reviewed manually for this provider.'],
      ['wait-ack', 'Kannel acknowledgement behavior has no direct beta converter mapping.'],
      ['validityperiod', 'Default validity period behavior must be reviewed per integration.'],
      ['my-number', 'MO address behavior must be reviewed with Sendium webhook/MO configuration.'],
      ['connect-allow-ip', 'Outbound SMPP source IP restrictions are deployment/network policy, not Sendium worker config.'],
      ['bind-addr-ton', 'Bind address TON is not mapped by the current converter.'],
      ['bind-addr-npi', 'Bind address NPI is not mapped by the current converter.'],
    ]),
  },
  'sendsms-user': {
    mapped: new Set(['username', 'password', 'user-allow-ip']),
    manual: new Map([
      ['user-deny-ip', 'Sendium credentials support allowed IPs, not deny lists. Convert this to an allow list manually.'],
      ['allowed-prefix', 'Prefix constraints should be implemented with Sendium routing or upstream policy.'],
      ['denied-prefix', 'Prefix deny rules should be implemented with Sendium routing or upstream policy.'],
      ['forced-smsc', 'Forced SMSC behavior should be reviewed against Sendium `routingTable.conf`.'],
      ['default-smsc', 'Default SMSC behavior should be reviewed against Sendium `routingTable.conf`.'],
      ['concatenation', 'HTTP concatenation behavior must be validated with the sending application and Sendium message handling.'],
      ['max-messages', 'Per-user message limits are not configured in Sendium credentials.'],
      ['dlr-url', 'Sendium accepts Kannel-style `dlr-url` per request; config-level defaults need manual migration.'],
      ['faked-sender', 'Sender override behavior should be reviewed against the sending application and routing rules.'],
    ]),
  },
  core: {
    mapped: new Set([]),
    manual: new Map([
      ['admin-port', 'Kannel admin HTTP service does not map to Sendium generated worker files.'],
      ['admin-password', 'Kannel admin credentials do not map to Sendium generated credential files.'],
      ['status-password', 'Kannel status credentials do not map to Sendium generated credential files.'],
      ['smsbox-port', 'Sendium uses its application HTTP port instead of Kannel bearerbox/smsbox ports.'],
      ['dlr-storage', 'DLR persistence behavior must be reviewed against Sendium runtime storage.'],
      ['store-type', 'Kannel store settings do not map directly to Sendium generated files.'],
      ['store-location', 'Kannel store settings do not map directly to Sendium generated files.'],
      ['log-file', 'Logging paths are deployment configuration in Sendium.'],
      ['log-level', 'Logging levels are deployment configuration in Sendium.'],
      ['access-log', 'HTTP access logging is controlled by Sendium/Quarkus runtime configuration.'],
      ['box-allow-ip', 'Network access controls should be reviewed at deployment level.'],
      ['box-deny-ip', 'Network access controls should be reviewed at deployment level.'],
    ]),
  },
  smsbox: {
    mapped: new Set([]),
    manual: new Map([
      ['bearerbox-host', 'Sendium does not use Kannel bearerbox/smsbox topology.'],
      ['sendsms-port', 'Sendium exposes its Kannel-compatible HTTP API on the application HTTP port.'],
      ['global-sender', 'Global sender defaults should be reviewed against sending applications or routing rules.'],
      ['sendsms-chars', 'HTTP charset handling should be validated with sending applications.'],
      ['log-file', 'Logging paths are deployment configuration in Sendium.'],
      ['log-level', 'Logging levels are deployment configuration in Sendium.'],
      ['access-log', 'HTTP access logging is controlled by Sendium/Quarkus runtime configuration.'],
      ['mo-recode', 'MO encoding behavior needs manual review with Sendium webhook configuration.'],
    ]),
  },
  smppbox: {
    mapped: new Set([
      'id',
      'smppbox-port',
      'port',
      'listen-port',
      'smpp-port',
      'host',
      'listen-host',
      'system-id',
      'systemid',
      'system_id',
      'username',
      'user',
      'password',
      'max-connections',
      'max-connections-per-ip',
      'throughput',
      'tps',
      'max-pending-submits',
      'window-size',
      'user-allow-ip',
      'allowed-ip',
      'allow-ip',
    ]),
    manual: new Map([
      ['bearerbox-host', 'Sendium does not use Kannel bearerbox/smppbox process links; review deployment topology manually.'],
      ['bearerbox-port', 'Sendium does not use Kannel bearerbox/smppbox process links; review deployment topology manually.'],
      ['log-file', 'Logging paths are deployment configuration in Sendium.'],
      ['log-level', 'Logging levels are deployment configuration in Sendium.'],
      ['route-to-smsc', 'SMPP ingress routing should be reviewed against Sendium `routingTable.conf`.'],
      ['allowed-prefix', 'Prefix constraints should be implemented with Sendium routing or upstream policy.'],
      ['denied-prefix', 'Prefix deny rules should be implemented with Sendium routing or upstream policy.'],
    ]),
  },
  'opensmppbox': {
    mapped: new Set([]),
    manual: new Map(),
  },
  'smppbox-user': {
    mapped: new Set(['system-id', 'systemid', 'system_id', 'username', 'user', 'password', 'user-allow-ip', 'allowed-ip', 'allow-ip', 'throughput', 'tps', 'max-connections']),
    manual: new Map([
      ['allowed-prefix', 'Prefix constraints should be implemented with Sendium routing or upstream policy.'],
      ['denied-prefix', 'Prefix deny rules should be implemented with Sendium routing or upstream policy.'],
      ['route-to-smsc', 'SMPP ingress routing should be reviewed against Sendium `routingTable.conf`.'],
    ]),
  },
};

KEY_CATALOG.opensmppbox = KEY_CATALOG.smppbox;

const STRUCTURAL_GROUPS = new Set(['core', 'smsbox']);

const REFERENCES = {
  kannel: {
    label: 'Kannel configuration guide',
    href: 'https://www.kannel.org/download/kannel-userguide-snapshot/userguide.html',
  },
  credentials: {
    label: 'Sendium credentials',
    href: 'https://github.com/cytechmobile/sendium/blob/main/docs/03-auth-security.md',
  },
  smpp: {
    label: 'Sendium SMPP configuration',
    href: 'https://github.com/cytechmobile/sendium/blob/main/docs/04-smpp-configuration.md',
  },
  routing: {
    label: 'Sendium routing engine',
    href: 'https://github.com/cytechmobile/sendium/blob/main/docs/05-routing-engine.md',
  },
  httpApi: {
    label: 'Sendium HTTP API',
    href: 'https://github.com/cytechmobile/sendium/blob/main/docs/06-http-api.md',
  },
  webhooks: {
    label: 'Sendium webhooks',
    href: 'https://github.com/cytechmobile/sendium/blob/main/docs/07-webhooks.md',
  },
  config: {
    label: 'Sendium configuration reference',
    href: 'https://github.com/cytechmobile/sendium/blob/main/docs/09-configuration-reference.md',
  },
  docker: {
    label: 'Sendium Docker deployment',
    href: 'https://github.com/cytechmobile/sendium/blob/main/docs/02-docker-deployment.md',
  },
};

const GROUP_REFERENCE_KEYS = {
  smsc: ['smpp', 'routing', 'kannel'],
  'sendsms-user': ['credentials', 'httpApi', 'routing', 'kannel'],
  core: ['config', 'docker', 'kannel'],
  smsbox: ['httpApi', 'webhooks', 'config', 'kannel'],
  smppbox: ['smpp', 'credentials', 'routing', 'kannel'],
  opensmppbox: ['smpp', 'credentials', 'routing', 'kannel'],
  'smppbox-user': ['credentials', 'smpp', 'routing', 'kannel'],
};

const KEY_REFERENCE_KEYS = {
  'allowed-smsc-id': ['routing', 'kannel'],
  'preferred-smsc-id': ['routing', 'kannel'],
  'denied-smsc-id': ['routing', 'kannel'],
  'forced-smsc': ['routing', 'kannel'],
  'default-smsc': ['routing', 'kannel'],
  'allowed-prefix': ['routing', 'kannel'],
  'denied-prefix': ['routing', 'kannel'],
  'user-allow-ip': ['credentials', 'kannel'],
  'user-deny-ip': ['credentials', 'kannel'],
  'dlr-url': ['httpApi', 'webhooks', 'kannel'],
  'log-file': ['config', 'kannel'],
  'log-level': ['config', 'kannel'],
  'access-log': ['config', 'kannel'],
  'bearerbox-host': ['smpp', 'httpApi', 'kannel'],
  'sendsms-port': ['httpApi', 'kannel'],
  'mo-recode': ['webhooks', 'kannel'],
  'smppbox-port': ['smpp', 'kannel'],
  'system-id': ['credentials', 'smpp', 'kannel'],
  systemid: ['credentials', 'smpp', 'kannel'],
  system_id: ['credentials', 'smpp', 'kannel'],
  'route-to-smsc': ['routing', 'kannel'],
};

export { SAMPLE_KANNEL_CONFIG, SAMPLE_SMPPBOX_CONFIG };

export function convertKannelConfig(source, smppboxSource = '') {
  const diagnostics = [];
  const groups = parseKannelConfig(source, diagnostics, 'kannel.conf');
  const smppboxGroups = parseKannelConfig(smppboxSource, diagnostics, 'smppbox.conf');
  const smppboxIngress = buildSmppboxIngress(smppboxGroups, diagnostics);
  const credentials = [...buildCredentials(groups, diagnostics), ...smppboxIngress.credentials];
  const smppClients = buildSmppClients(groups, diagnostics);
  const allGroups = [...groups, ...smppboxGroups];
  const enrichedDiagnostics = diagnostics.map(addGuidanceToDiagnostic);

  return {
    groups: allGroups,
    diagnostics: enrichedDiagnostics,
    files: {
      'credentials.yml': renderCredentials(credentials),
      'smsg.properties': renderSmsgProperties(smppClients, smppboxIngress.servers),
      'routingTable.conf': renderRoutingTable(smppClients, smppboxIngress.servers),
    },
    summary: {
      groups: allGroups.length,
      credentials: credentials.length,
      smppClients: smppClients.length,
      smppServers: smppboxIngress.servers.length,
      warnings: enrichedDiagnostics.filter((diagnostic) => diagnostic.severity === 'warning').length,
      errors: enrichedDiagnostics.filter((diagnostic) => diagnostic.severity === 'error').length,
    },
  };
}

function addGuidanceToDiagnostic(diagnostic) {
  const referenceKeys = KEY_REFERENCE_KEYS[diagnostic.key] || GROUP_REFERENCE_KEYS[diagnostic.group] || ['config', 'kannel'];
  const references = referenceKeys.map((key) => REFERENCES[key]);

  return {
    ...diagnostic,
    nextStep: diagnostic.nextStep || buildNextStep(diagnostic),
    references,
  };
}

function buildNextStep(diagnostic) {
  if (diagnostic.severity === 'error') {
    return 'Fix the missing or invalid source value before using the generated file in a Sendium deployment.';
  }

  if (diagnostic.key) {
    return `Review Kannel \`${diagnostic.key}\` behavior and decide whether it belongs in Sendium config, routing, deployment, or application code.`;
  }

  if (diagnostic.group) {
    return `Review Kannel \`group = ${diagnostic.group}\` manually; the converter only maps a narrow subset automatically.`;
  }

  return 'Review this item manually before using the generated Sendium files.';
}

function parseKannelConfig(source, diagnostics, sourceName) {
  const groups = [];
  let currentGroup = null;

  source.split(/\r?\n/).forEach((rawLine, index) => {
    const lineNumber = index + 1;
    const line = removeInlineComment(rawLine).trim();

    if (!line) {
      return;
    }

    const separatorIndex = line.indexOf('=');
    if (separatorIndex === -1) {
      diagnostics.push({
        severity: 'error',
        source: sourceName,
        line: lineNumber,
        message: 'Could not parse line because it does not contain key = value syntax.',
        sourceLine: rawLine.trim(),
      });
      return;
    }

    const key = normalizeKey(line.slice(0, separatorIndex));
    const value = normalizeValue(line.slice(separatorIndex + 1));

    if (key === 'group') {
      currentGroup = {
        type: normalizeKey(value),
        source: sourceName,
        line: lineNumber,
        entries: [],
      };
      groups.push(currentGroup);
      return;
    }

    if (!currentGroup) {
      diagnostics.push({
        severity: 'warning',
        source: sourceName,
        line: lineNumber,
        key,
        message: `Ignored \`${key}\` because it appears before any Kannel group declaration.`,
        sourceLine: rawLine.trim(),
      });
      return;
    }

    currentGroup.entries.push({ key, value, line: lineNumber, source: sourceName, sourceLine: rawLine.trim() });
  });

  return groups;
}

function buildCredentials(groups, diagnostics) {
  const credentials = [];

  groups
    .filter((group) => group.type === 'sendsms-user')
    .forEach((group, index) => {
      const username = getEntryValue(group, 'username');
      const password = getEntryValue(group, 'password');
      const allowedIps = splitCsv(getEntryValue(group, 'user-allow-ip'));

      if (!username || !password) {
        diagnostics.push({
          severity: 'error',
          source: group.source,
          line: group.line,
          group: group.type,
          message: 'Sendium HTTP credentials require both username and password.',
        });
        return;
      }

      credentials.push({
        type: 'HTTP',
        systemId: username,
        password,
        allowedIps,
        sourceName: `sendsms-user ${index + 1}`,
      });

      diagnoseCatalogKeys(group, diagnostics);
    });

  if (credentials.length === 0) {
    diagnostics.push({
        severity: 'warning',
        source: 'kannel.conf',
        message: 'No complete `group = sendsms-user` block was found, so `credentials.yml` contains only guidance comments.',
      });
  }

  return credentials;
}

function buildSmppClients(groups, diagnostics) {
  const usedNames = new Set();
  const clients = [];

  groups
    .filter((group) => group.type === 'smsc')
    .forEach((group) => {
      const protocol = getEntryValue(group, 'smsc');

      if (protocol && protocol.toLowerCase() !== 'smpp') {
        diagnostics.push({
          severity: 'warning',
          source: group.source,
          line: group.line,
          group: group.type,
          message: `Only SMPP SMSC blocks are converted by this beta converter. Found \`smsc = ${protocol}\`.`,
        });
        diagnoseCatalogKeys(group, diagnostics);
        return;
      }

      const id = getEntryValue(group, 'smsc-id') || getEntryValue(group, 'id') || getEntryValue(group, 'host') || 'smppClient';
      const instanceName = uniqueInstanceName(toInstanceName(id), usedNames);
      const host = getEntryValue(group, 'host');
      const port = getEntryValue(group, 'port');
      const username = getEntryValue(group, 'smsc-username') || getEntryValue(group, 'username');
      const password = getEntryValue(group, 'smsc-password') || getEntryValue(group, 'password');

      for (const [key, value] of [
        ['host', host],
        ['port', port],
        ['smsc-username', username],
        ['smsc-password', password],
      ]) {
        if (!value) {
          diagnostics.push({
            severity: 'error',
            source: group.source,
            line: group.line,
            group: group.type,
            key,
            message: `SMPP client \`${instanceName}\` is missing \`${key}\`, so the generated value needs manual review.`,
          });
        }
      }

      clients.push({
        instanceName,
        sourceId: id,
        host: host || '<provider-host>',
        port: port || '2775',
        username: username || '<upstream-system-id>',
        password: password || '<upstream-password>',
        tps: getEntryValue(group, 'throughput') || '0',
        systemType: getEntryValue(group, 'system-type'),
        enquireLinkMillis: secondsToMillis(getEntryValue(group, 'enquire-link-interval') || getEntryValue(group, 'keepalive')),
        reconnectMillis: secondsToMillis(getEntryValue(group, 'reconnect-delay')),
        interfaceVersion: normalizeInterfaceVersion(getEntryValue(group, 'interface-version')),
        addressRange: getEntryValue(group, 'address-range'),
        sourceTon: getEntryValue(group, 'source-addr-ton'),
        sourceNpi: getEntryValue(group, 'source-addr-npi'),
        destTon: getEntryValue(group, 'dest-addr-ton'),
        destNpi: getEntryValue(group, 'dest-addr-npi'),
        bindMode: parseBindMode(getEntryValue(group, 'transceiver-mode')),
      });

      diagnoseCatalogKeys(group, diagnostics);
    });

  if (clients.length === 0) {
    diagnostics.push({
      severity: 'warning',
      source: 'kannel.conf',
      message: 'No SMPP `group = smsc` block was found, so `smsg.properties` contains only guidance comments.',
    });
  }

  groups
    .filter((group) => STRUCTURAL_GROUPS.has(group.type))
    .forEach((group) => {
      diagnostics.push({
        severity: 'warning',
        source: group.source,
        line: group.line,
        group: group.type,
        message: `Kannel \`group = ${group.type}\` contains service-level settings that need manual Sendium deployment review.`,
      });
      diagnoseCatalogKeys(group, diagnostics);
    });

  groups
    .filter((group) => !['smsc', 'sendsms-user', ...STRUCTURAL_GROUPS].includes(group.type))
    .forEach((group) => {
      diagnostics.push({
        severity: 'warning',
        source: group.source,
        line: group.line,
        group: group.type,
        message: `Kannel \`group = ${group.type}\` is not converted by the current converter.`,
      });
      diagnoseCatalogKeys(group, diagnostics);
    });

  return clients;
}

function buildSmppboxIngress(groups, diagnostics) {
  const smppboxGroups = groups.filter((group) => ['smppbox', 'opensmppbox'].includes(group.type));
  const userGroups = groups.filter((group) => group.type === 'smppbox-user');
  const servers = [];
  const credentials = [];
  const usedServerNames = new Set();

  smppboxGroups.forEach((group, index) => {
    const id = getEntryValue(group, 'id') || `smppIngress${index + 1}`;
    const instanceName = uniqueInstanceName(toInstanceName(id), usedServerNames);
    const port = getFirstEntryValue(group, ['smppbox-port', 'port', 'listen-port', 'smpp-port']) || '27777';
    const host = getFirstEntryValue(group, ['host', 'listen-host']);

    servers.push({
      instanceName,
      sourceId: id,
      port,
      host,
      maxConnections: getEntryValue(group, 'max-connections') || '1000',
      maxConnectionsPerIp: getEntryValue(group, 'max-connections-per-ip'),
      maxConnectionsPerUser: getEntryValue(group, 'max-connections-per-user') || getEntryValue(group, 'max-connections') || '4',
      maxRate: getEntryValue(group, 'throughput') || getEntryValue(group, 'tps') || '0',
      maxPending: getEntryValue(group, 'max-pending-submits') || getEntryValue(group, 'window-size') || '1000',
    });

    addSmppCredentialFromGroup(group, credentials, diagnostics, `smppbox ${index + 1}`);
    diagnoseCatalogKeys(group, diagnostics);
  });

  userGroups.forEach((group, index) => {
    addSmppCredentialFromGroup(group, credentials, diagnostics, `smppbox-user ${index + 1}`);
    diagnoseCatalogKeys(group, diagnostics);
  });

  groups
    .filter((group) => !['smppbox', 'opensmppbox', 'smppbox-user'].includes(group.type))
    .forEach((group) => {
      diagnostics.push({
        severity: 'warning',
        source: group.source,
        line: group.line,
        group: group.type,
        message: `SMPP ingress input contains unsupported \`group = ${group.type}\`; only smppbox-style groups are converted.`,
      });
      diagnoseCatalogKeys(group, diagnostics);
    });

  if (groups.length > 0 && servers.length === 0) {
    diagnostics.push({
      severity: 'warning',
      source: 'smppbox.conf',
      message: 'No `group = smppbox` block was found, so no Sendium SMPP server was generated.',
    });
  }

  return { servers, credentials };
}

function addSmppCredentialFromGroup(group, credentials, diagnostics, sourceName) {
  const systemId = getFirstEntryValue(group, ['system-id', 'systemid', 'system_id', 'username', 'user']);
  const password = getEntryValue(group, 'password');

  if (!systemId && !password) {
    return;
  }

  if (!systemId || !password) {
    diagnostics.push({
      severity: 'error',
      source: group.source,
      line: group.line,
      group: group.type,
      message: 'Sendium SMPP credentials require both system-id and password.',
    });
    return;
  }

  credentials.push({
    type: 'SMPP',
    systemId,
    password,
    allowedIps: splitCsv(getFirstEntryValue(group, ['user-allow-ip', 'allowed-ip', 'allow-ip'])),
    sourceName,
  });
}

function renderCredentials(credentials) {
  const lines = [
    '# Generated by Sendium Kannel Converter',
    '# Review secrets, IP restrictions, and account metadata before production use.',
    'credentials:',
  ];

  if (credentials.length === 0) {
    lines.push('  # Add HTTP credentials converted from Kannel `group = sendsms-user` blocks.');
    return lines.join('\n');
  }

  credentials.forEach((credential) => {
    lines.push(`  - type: ${credential.type}`);
    lines.push(`    systemId: ${quoteYaml(credential.systemId)}`);
    lines.push(`    password: ${quoteYaml(credential.password)}`);

    if (credential.allowedIps.length > 0) {
      lines.push('    allowedIps:');
      credential.allowedIps.forEach((ip) => lines.push(`      - ${quoteYaml(ip)}`));
    }
  });

  return lines.join('\n');
}

function renderSmsgProperties(clients, smppServers = []) {
  const lines = [
    '# Generated by Sendium Kannel Converter',
    '# Review all upstream credentials and provider-specific SMPP options before production use.',
  ];

  if (clients.length === 0 && smppServers.length === 0) {
    lines.push('# Add outSms.instance.<name> blocks for Sendium SMPP clients or servers.');
    return lines.join('\n');
  }

  smppServers.forEach((server) => {
    lines.push('');
    lines.push(`# Converted from smppbox ingress: ${server.sourceId}`);
    lines.push(`outSms.instance.${server.instanceName}.enable = true`);
    lines.push(`outSms.instance.${server.instanceName}.type = smppserver`);
    lines.push(`outSms.instance.${server.instanceName}.srv.port = ${server.port}`);

    if (server.host) {
      lines.push(`outSms.instance.${server.instanceName}.srv.host = ${server.host}`);
    }

    lines.push(`outSms.instance.${server.instanceName}.srv.maxConnections = ${server.maxConnections}`);

    if (server.maxConnectionsPerIp) {
      lines.push(`outSms.instance.${server.instanceName}.srv.maxConnectionsPerIP = ${server.maxConnectionsPerIp}`);
    }

    lines.push(`outSms.instance.${server.instanceName}.conf.maxConnectionsPerUser.default = ${server.maxConnectionsPerUser}`);
    lines.push(`outSms.instance.${server.instanceName}.conf.maxRate.default = ${server.maxRate}`);
    lines.push(`outSms.instance.${server.instanceName}.conf.maxPending.default = ${server.maxPending}`);
  });

  clients.forEach((client) => {
    lines.push('');
    lines.push(`# Converted from Kannel SMSC: ${client.sourceId}`);
    lines.push(`outSms.instance.${client.instanceName}.enable = true`);
    lines.push(`outSms.instance.${client.instanceName}.type = smppclient`);
    lines.push(`outSms.instance.${client.instanceName}.host = ${client.host}`);
    lines.push(`outSms.instance.${client.instanceName}.port = ${client.port}`);
    lines.push(`outSms.instance.${client.instanceName}.username = ${client.username}`);
    lines.push(`outSms.instance.${client.instanceName}.password = ${client.password}`);
    lines.push(`outSms.instance.${client.instanceName}.tps = ${client.tps}`);
    lines.push(`outSms.instance.${client.instanceName}.connections.transceivers = ${client.bindMode.transceivers}`);
    lines.push(`outSms.instance.${client.instanceName}.connections.transmitters = ${client.bindMode.transmitters}`);
    lines.push(`outSms.instance.${client.instanceName}.connections.receivers = ${client.bindMode.receivers}`);

    if (client.systemType) {
      lines.push(`outSms.instance.${client.instanceName}.systemType = ${client.systemType}`);
    }

    if (client.enquireLinkMillis) {
      lines.push(`outSms.instance.${client.instanceName}.enquire.link.interval.millis = ${client.enquireLinkMillis}`);
    }

    if (client.reconnectMillis) {
      lines.push(`outSms.instance.${client.instanceName}.reconnect.interval.millis = ${client.reconnectMillis}`);
    }

    if (client.interfaceVersion) {
      lines.push(`outSms.instance.${client.instanceName}.interfaceVersion = ${client.interfaceVersion}`);
    }

    if (client.addressRange) {
      lines.push(`outSms.instance.${client.instanceName}.addressRange = ${client.addressRange}`);
    }

    if (client.sourceTon) {
      lines.push(`outSms.instance.${client.instanceName}.src.addr.ton = ${client.sourceTon}`);
    }

    if (client.sourceNpi) {
      lines.push(`outSms.instance.${client.instanceName}.src.addr.npi = ${client.sourceNpi}`);
    }

    if (client.destTon) {
      lines.push(`outSms.instance.${client.instanceName}.dest.addr.ton = ${client.destTon}`);
    }

    if (client.destNpi) {
      lines.push(`outSms.instance.${client.instanceName}.dest.addr.npi = ${client.destNpi}`);
    }
  });

  return lines.join('\n');
}

function renderRoutingTable(clients, smppServers = []) {
  const firstClient = clients[0]?.instanceName || '<manual-smpp-client>';
  const firstServerTarget = smppServers[0] ? `smppserver.${smppServers[0].instanceName}:type:==:18` : '# smppserver.<instance>:type:==:18';

  return [
    '# Minimal starter routing generated by Sendium Kannel Converter',
    '# Kannel routing is not deeply converted by this beta converter. Review docs/05-routing-engine.md.',
    '[default]',
    'MESSAGE:type:==:0',
    'MESSAGE:type:==:11',
    'MESSAGE:type:==:14',
    'MESSAGE:type:==:17',
    'MESSAGE:type:==:10',
    firstServerTarget,
    '',
    '[MESSAGE]',
    `${firstClient}::default:`,
  ].join('\n');
}

function diagnoseCatalogKeys(group, diagnostics) {
  const catalog = KEY_CATALOG[group.type];

  group.entries.forEach((entry) => {
    if (!catalog) {
      diagnostics.push({
        severity: 'warning',
        source: entry.source,
        line: entry.line,
        group: group.type,
        key: entry.key,
        message: `Kannel \`${entry.key}\` belongs to an unsupported \`group = ${group.type}\` block.`,
      });
      return;
    }

    if (catalog.mapped.has(entry.key)) {
      return;
    }

    const manualMessage = catalog.manual.get(entry.key);
    diagnostics.push({
    severity: 'warning',
    source: entry.source,
    line: entry.line,
      group: group.type,
      key: entry.key,
      message: manualMessage || `Kannel \`${entry.key}\` is not in the current mapping catalog and needs manual review.`,
    });
  });
}

function normalizeInterfaceVersion(value) {
  if (!value) {
    return '';
  }

  if (value === '34' || value === '3.4') {
    return '52';
  }

  if (value === '33' || value === '3.3') {
    return '51';
  }

  return value;
}

function getEntryValue(group, key) {
  return group.entries.find((entry) => entry.key === key)?.value || '';
}

function getFirstEntryValue(group, keys) {
  for (const key of keys) {
    const value = getEntryValue(group, key);
    if (value) {
      return value;
    }
  }

  return '';
}

function removeInlineComment(line) {
  let quote = null;

  for (let index = 0; index < line.length; index += 1) {
    const character = line[index];

    if ((character === '"' || character === "'") && line[index - 1] !== '\\') {
      quote = quote === character ? null : quote || character;
      continue;
    }

    if (!quote && (character === '#' || character === ';')) {
      return line.slice(0, index);
    }
  }

  return line;
}

function normalizeKey(value) {
  return value.trim().toLowerCase();
}

function normalizeValue(value) {
  const trimmed = value.trim();
  if ((trimmed.startsWith('"') && trimmed.endsWith('"')) || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
    return trimmed.slice(1, -1);
  }
  return trimmed;
}

function splitCsv(value) {
  if (!value) {
    return [];
  }

  return value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
}

function toInstanceName(value) {
  const normalized = value
    .trim()
    .replace(/[^a-zA-Z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');

  if (!normalized) {
    return 'smppClient';
  }

  return normalized
    .split('-')
    .map((part, index) => {
      if (index === 0) {
        return part.charAt(0).toLowerCase() + part.slice(1);
      }
      return part.charAt(0).toUpperCase() + part.slice(1);
    })
    .join('');
}

function uniqueInstanceName(name, usedNames) {
  let candidate = name;
  let suffix = 2;

  while (usedNames.has(candidate)) {
    candidate = `${name}${suffix}`;
    suffix += 1;
  }

  usedNames.add(candidate);
  return candidate;
}

function secondsToMillis(value) {
  if (!value) {
    return '';
  }

  const seconds = Number(value);
  if (!Number.isFinite(seconds)) {
    return '';
  }

  return String(seconds * 1000);
}

function parseBindMode(value) {
  if (value === 'false') {
    return { transceivers: 0, transmitters: 1, receivers: 1 };
  }

  return { transceivers: 1, transmitters: 0, receivers: 0 };
}

function quoteYaml(value) {
  return JSON.stringify(value);
}
