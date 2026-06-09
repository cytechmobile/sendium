const SAMPLE_KANNEL_CONFIG = `# Minimal Kannel-style example
group = core
admin-port = 13000
smsbox-port = 13001

group = sendsms-user
username = legacy-http-user
password = change-me
user-allow-ip = 10.0.0.15
forced-smsc = upstream-a
allowed-prefix = 447, 448
denied-prefix = 447999

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
preferred-smsc-id = vip-a
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
route-to-smsc = upstream-a
allowed-prefix = 447
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
      'allowed-smsc-id',
      'preferred-smsc-id',
    ]),
    manual: new Map([
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
    mapped: new Set(['username', 'password', 'user-allow-ip', 'forced-smsc', 'default-smsc', 'allowed-prefix', 'denied-prefix']),
    manual: new Map([
      ['user-deny-ip', 'Sendium credentials support allowed IPs, not deny lists. Convert this to an allow list manually.'],
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
      'route-to-smsc',
      'allowed-prefix',
      'denied-prefix',
    ]),
    manual: new Map([
      ['bearerbox-host', 'Sendium does not use Kannel bearerbox/smppbox process links; review deployment topology manually.'],
      ['bearerbox-port', 'Sendium does not use Kannel bearerbox/smppbox process links; review deployment topology manually.'],
      ['log-file', 'Logging paths are deployment configuration in Sendium.'],
      ['log-level', 'Logging levels are deployment configuration in Sendium.'],
    ]),
  },
  'opensmppbox': {
    mapped: new Set([]),
    manual: new Map(),
  },
  'smppbox-user': {
    mapped: new Set(['system-id', 'systemid', 'system_id', 'username', 'user', 'password', 'user-allow-ip', 'allowed-ip', 'allow-ip', 'throughput', 'tps', 'max-connections', 'route-to-smsc', 'allowed-prefix', 'denied-prefix']),
    manual: new Map([]),
  },
  'smsbox-route': {
    mapped: new Set([]),
    manual: new Map([
      ['smsbox-id', 'Kannel smsbox route topology is not converted; review whether this belongs in Sendium deployment topology, SMPP ingress, or webhook/application routing.'],
      ['smsbox', 'Kannel smsbox route topology is not converted; review whether this belongs in Sendium deployment topology, SMPP ingress, or webhook/application routing.'],
      ['smsc-id', 'Kannel smsbox-to-SMSC route constraints are not converted into Sendium outbound routing rules.'],
      ['shortcode', 'Kannel shortcode smsbox routing needs manual migration to Sendium ingress or application-level handling.'],
    ]),
  },
  'smsc-route': {
    mapped: new Set([]),
    manual: new Map([
      ['smsc-id', 'Kannel SMSC route group targets need manual review against generated Sendium worker names and route ordering.'],
      ['smsc', 'Kannel SMSC route group targets need manual review against generated Sendium worker names and route ordering.'],
      ['prefix', 'Kannel route-group prefix behavior is warning-only; translate manually if it should become Sendium to:startsWith routing.'],
      ['receiver', 'Kannel receiver route constraints need manual review against Sendium to/from routing fields.'],
      ['sender', 'Kannel sender route constraints need manual review against Sendium from routing fields.'],
      ['account', 'Kannel route account constraints need manual review because Sendium owner_id may come from HTTP account or credential context.'],
    ]),
  },
  'sms-service': {
    mapped: new Set([]),
    manual: new Map([
      ['keyword', 'Kannel keyword service routing maps to Sendium MO webhook or application logic, not outbound routingTable.conf.'],
      ['aliases', 'Kannel keyword aliases need manual migration to Sendium MO webhook or application logic.'],
      ['catch-all', 'Kannel catch-all service behavior needs manual migration to Sendium MO webhook or application logic.'],
      ['text', 'Kannel static SMS service responses are not generated by the converter.'],
      ['get-url', 'Kannel HTTP service callbacks should be reviewed against Sendium MO webhook/application handling.'],
      ['post-url', 'Kannel HTTP service callbacks should be reviewed against Sendium MO webhook/application handling.'],
      ['accepted-smsc', 'Kannel service SMSC allow lists do not map to outbound Sendium routing rules.'],
      ['accepted-account', 'Kannel service account allow lists need manual migration to application or credential policy.'],
      ['max-messages', 'Kannel service message limits are application behavior and are not generated by the converter.'],
    ]),
  },
};

KEY_CATALOG.opensmppbox = KEY_CATALOG.smppbox;

const STRUCTURAL_GROUPS = new Set(['core', 'smsbox']);

const ROUTING_ADJACENT_GROUP_MESSAGES = new Map([
  ['smsbox-route', 'Kannel `group = smsbox-route` describes smsbox routing topology; the beta converter does not turn it into active Sendium routes.'],
  ['smsc-route', 'Kannel `group = smsc-route` describes explicit SMSC route topology; review it manually before editing generated Sendium routingTable.conf.'],
  ['sms-service', 'Kannel `group = sms-service` describes MO/keyword service behavior; migrate it through Sendium webhooks or application logic rather than outbound routingTable.conf.'],
]);

const ROUTING_COMPATIBILITY_STATUS = Object.freeze({
  MAPPED_ACTIVE: 'mapped-active',
  WARNING_ONLY: 'warning-only',
  UNSUPPORTED: 'unsupported',
  RUNTIME_NEEDED: 'runtime-needed',
});

const ROUTING_COMPATIBILITY_MATRIX = Object.freeze([
  {
    id: 'sendsms-user.forced-smsc',
    groups: ['sendsms-user'],
    key: 'forced-smsc',
    status: ROUTING_COMPATIBILITY_STATUS.MAPPED_ACTIVE,
    category: 'outbound-smsc-selection',
    sendiumSurfaces: ['routingTable.conf', 'StandardMessage.owner_id'],
    activeRouteGeneration: true,
    note: 'Maps to an account-scoped route when the target resolves to a converted SMPP client.',
  },
  {
    id: 'sendsms-user.default-smsc',
    groups: ['sendsms-user'],
    key: 'default-smsc',
    status: ROUTING_COMPATIBILITY_STATUS.MAPPED_ACTIVE,
    category: 'outbound-smsc-selection',
    sendiumSurfaces: ['routingTable.conf', 'StandardMessage.owner_id'],
    activeRouteGeneration: true,
    note: 'Currently maps like forced-smsc; request-level smsc override semantics need a Phase 7 diagnostic pass.',
  },
  {
    id: 'sendsms-user.allowed-prefix',
    groups: ['sendsms-user'],
    key: 'allowed-prefix',
    status: ROUTING_COMPATIBILITY_STATUS.MAPPED_ACTIVE,
    category: 'prefix-policy',
    sendiumSurfaces: ['routingTable.conf', 'StandardMessage.to'],
    activeRouteGeneration: true,
    note: 'Maps to destination prefix route-selection conditions when a route target is resolvable.',
  },
  {
    id: 'sendsms-user.denied-prefix',
    groups: ['sendsms-user'],
    key: 'denied-prefix',
    status: ROUTING_COMPATIBILITY_STATUS.MAPPED_ACTIVE,
    category: 'submission-policy',
    sendiumSurfaces: ['routingTable.conf', 'StandardMessage.to'],
    activeRouteGeneration: true,
    note: 'Adds negated prefix conditions when scoped to an active route, but still needs warning-only rejection-policy review.',
  },
  {
    id: 'smsc.preferred-smsc-id',
    groups: ['smsc'],
    key: 'preferred-smsc-id',
    status: ROUTING_COMPATIBILITY_STATUS.MAPPED_ACTIVE,
    category: 'request-override',
    sendiumSurfaces: ['routingTable.conf', 'StandardMessage.message_center'],
    activeRouteGeneration: true,
    note: 'Maps request smsc selectors to message_center routes when the owning SMSC resolves to a converted client.',
  },
  {
    id: 'smsc.allowed-smsc-id',
    groups: ['smsc'],
    key: 'allowed-smsc-id',
    status: ROUTING_COMPATIBILITY_STATUS.MAPPED_ACTIVE,
    category: 'request-override',
    sendiumSurfaces: ['routingTable.conf', 'StandardMessage.message_center'],
    activeRouteGeneration: true,
    note: 'Maps request smsc selectors to message_center routes after preferred selectors.',
  },
  {
    id: 'smsc.denied-smsc-id',
    groups: ['smsc'],
    key: 'denied-smsc-id',
    status: ROUTING_COMPATIBILITY_STATUS.WARNING_ONLY,
    category: 'submission-policy',
    sendiumSurfaces: ['manual migration', 'runtime rejection/filter support'],
    activeRouteGeneration: false,
    note: 'Deny semantics require rejection behavior, not only alternate route selection.',
  },
  {
    id: 'smppbox.route-to-smsc',
    groups: ['smppbox', 'opensmppbox', 'smppbox-user'],
    key: 'route-to-smsc',
    status: ROUTING_COMPATIBILITY_STATUS.MAPPED_ACTIVE,
    category: 'ingress-routing',
    sendiumSurfaces: ['routingTable.conf', 'StandardMessage.owner_id'],
    activeRouteGeneration: true,
    note: 'Maps to an account-scoped route when the ingress account and target SMSC are resolvable.',
  },
  {
    id: 'smppbox.allowed-prefix',
    groups: ['smppbox', 'opensmppbox', 'smppbox-user'],
    key: 'allowed-prefix',
    status: ROUTING_COMPATIBILITY_STATUS.MAPPED_ACTIVE,
    category: 'prefix-policy',
    sendiumSurfaces: ['routingTable.conf', 'StandardMessage.to'],
    activeRouteGeneration: true,
    note: 'Maps to destination prefix route-selection conditions when tied to route-to-smsc.',
  },
  {
    id: 'smppbox.denied-prefix',
    groups: ['smppbox', 'opensmppbox', 'smppbox-user'],
    key: 'denied-prefix',
    status: ROUTING_COMPATIBILITY_STATUS.MAPPED_ACTIVE,
    category: 'submission-policy',
    sendiumSurfaces: ['routingTable.conf', 'StandardMessage.to'],
    activeRouteGeneration: true,
    note: 'Adds negated prefix conditions when scoped to an active route, but still needs warning-only rejection-policy review.',
  },
  {
    id: 'smsbox-route.group',
    groups: ['smsbox-route'],
    key: null,
    status: ROUTING_COMPATIBILITY_STATUS.UNSUPPORTED,
    category: 'mo-service-routing',
    sendiumSurfaces: ['webhook/MO config', 'manual migration'],
    activeRouteGeneration: false,
    note: 'Kannel smsbox route topology is not converted into outbound Sendium routing rules.',
  },
  {
    id: 'smsc-route.group',
    groups: ['smsc-route'],
    key: null,
    status: ROUTING_COMPATIBILITY_STATUS.UNSUPPORTED,
    category: 'outbound-smsc-selection',
    sendiumSurfaces: ['routingTable.conf', 'manual migration'],
    activeRouteGeneration: false,
    note: 'Dedicated route groups need a later diagnostics pass before any generated route rules are safe.',
  },
  {
    id: 'sms-service.group',
    groups: ['sms-service'],
    key: null,
    status: ROUTING_COMPATIBILITY_STATUS.RUNTIME_NEEDED,
    category: 'mo-service-routing',
    sendiumSurfaces: ['webhook/MO config', 'application code'],
    activeRouteGeneration: false,
    note: 'Kannel keyword/service behavior maps to MO webhook or application behavior rather than outbound routingTable.conf.',
  },
]);

const ROUTING_COMPATIBILITY_BY_GROUP_AND_KEY = new Map();
const ROUTING_COMPATIBILITY_BY_GROUP = new Map();

ROUTING_COMPATIBILITY_MATRIX.forEach((entry) => {
  entry.groups.forEach((group) => {
    if (entry.key) {
      ROUTING_COMPATIBILITY_BY_GROUP_AND_KEY.set(`${group}:${entry.key}`, entry);
    } else {
      ROUTING_COMPATIBILITY_BY_GROUP.set(group, entry);
    }
  });
});

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
  'smsbox-route': ['routing', 'webhooks', 'kannel'],
  'smsc-route': ['routing', 'smpp', 'kannel'],
  'sms-service': ['webhooks', 'routing', 'kannel'],
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
  keyword: ['webhooks', 'kannel'],
  aliases: ['webhooks', 'kannel'],
  'catch-all': ['webhooks', 'kannel'],
  'get-url': ['webhooks', 'kannel'],
  'post-url': ['webhooks', 'kannel'],
  'accepted-smsc': ['webhooks', 'routing', 'kannel'],
  'accepted-account': ['webhooks', 'credentials', 'kannel'],
};

export { SAMPLE_KANNEL_CONFIG, SAMPLE_SMPPBOX_CONFIG };

export function getRoutingCompatibilityMatrix() {
  return ROUTING_COMPATIBILITY_MATRIX.map((entry) => ({
    ...entry,
    groups: [...entry.groups],
    sendiumSurfaces: [...entry.sendiumSurfaces],
  }));
}

export function convertKannelConfig(source, smppboxSource = '') {
  const diagnostics = [];
  const groups = parseKannelConfig(source, diagnostics, 'kannel.conf');
  const smppboxGroups = parseKannelConfig(smppboxSource, diagnostics, 'smppbox.conf');
  const smppboxIngress = buildSmppboxIngress(smppboxGroups, diagnostics);
  const credentials = [...buildCredentials(groups, diagnostics), ...smppboxIngress.credentials];
  const smppClients = buildSmppClients(groups, diagnostics);
  const routingModel = buildRoutingModel(groups, smppboxGroups, smppClients, smppboxIngress.servers, diagnostics);
  const allGroups = [...groups, ...smppboxGroups];
  addOutboundRouteSelectionDiagnostics(groups, buildClientRouteTargetMap(smppClients), diagnostics);
  const routingCompatibility = buildRoutingCompatibilityReport(allGroups, diagnostics);
  const enrichedDiagnostics = diagnostics
    .map(addRoutingCompatibilityToDiagnostic)
    .map(addGuidanceToDiagnostic);

  return {
    groups: allGroups,
    diagnostics: enrichedDiagnostics,
    routingCompatibility,
    files: {
      'credentials.yml': renderCredentials(credentials),
      'smsg.properties': renderSmsgProperties(smppClients, smppboxIngress.servers),
      'routingTable.conf': renderRoutingTable(routingModel),
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

function addRoutingCompatibilityToDiagnostic(diagnostic) {
  const matrixEntry = getRoutingCompatibilityForDiagnostic(diagnostic);

  if (!matrixEntry) {
    return diagnostic;
  }

  return {
    ...diagnostic,
    routingCompatibilityId: matrixEntry.id,
    routingStatus: getDiagnosticRoutingStatus(diagnostic, matrixEntry),
    routingCategory: matrixEntry.category,
    routingSurfaces: [...matrixEntry.sendiumSurfaces],
  };
}

function getRoutingCompatibilityForDiagnostic(diagnostic) {
  if (!diagnostic.group) {
    return null;
  }

  if (diagnostic.key) {
    const keyEntry = ROUTING_COMPATIBILITY_BY_GROUP_AND_KEY.get(`${diagnostic.group}:${diagnostic.key}`);
    if (keyEntry) {
      return keyEntry;
    }
  }

  return ROUTING_COMPATIBILITY_BY_GROUP.get(diagnostic.group) || null;
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

function buildRoutingCompatibilityReport(groups, diagnostics = []) {
  const entries = [];

  groups.forEach((group) => {
    const groupMatrixEntry = ROUTING_COMPATIBILITY_BY_GROUP.get(group.type);
    if (groupMatrixEntry) {
      entries.push(toRoutingCompatibilityEntry(groupMatrixEntry, group, null, diagnostics));
    }

    group.entries.forEach((entry) => {
      const matrixEntry = ROUTING_COMPATIBILITY_BY_GROUP_AND_KEY.get(`${group.type}:${entry.key}`);
      if (matrixEntry) {
        entries.push(toRoutingCompatibilityEntry(matrixEntry, group, entry, diagnostics));
      }
    });
  });

  return {
    entries,
    summary: summarizeRoutingCompatibility(entries),
  };
}

function toRoutingCompatibilityEntry(matrixEntry, group, entry = null, diagnostics = []) {
  const status = getSourceRoutingStatus(matrixEntry, group, entry, diagnostics);

  return {
    id: matrixEntry.id,
    source: entry?.source || group.source,
    line: entry?.line || group.line,
    group: group.type,
    key: entry?.key || matrixEntry.key,
    value: entry?.value,
    status,
    category: matrixEntry.category,
    sendiumSurfaces: [...matrixEntry.sendiumSurfaces],
    activeRouteGeneration: matrixEntry.activeRouteGeneration,
    note: matrixEntry.note,
  };
}

function getSourceRoutingStatus(matrixEntry, group, entry, diagnostics) {
  if (entry && isUnmappedRoutingSource(group, entry, diagnostics)) {
    return ROUTING_COMPATIBILITY_STATUS.WARNING_ONLY;
  }

  return matrixEntry.status;
}

function getDiagnosticRoutingStatus(diagnostic, matrixEntry) {
  if (isUnmappedRoutingDiagnostic(diagnostic)) {
    return ROUTING_COMPATIBILITY_STATUS.WARNING_ONLY;
  }

  return matrixEntry.status;
}

function isUnmappedRoutingSource(group, entry, diagnostics) {
  return diagnostics.some((diagnostic) => diagnostic.source === entry.source
    && diagnostic.line === entry.line
    && diagnostic.group === group.type
    && diagnostic.key === entry.key
    && isUnmappedRoutingDiagnostic(diagnostic));
}

function isUnmappedRoutingDiagnostic(diagnostic) {
  return /Could not map|no resolvable|does not match a converted|missing|leaves default behavior/.test(diagnostic.message || '');
}

function summarizeRoutingCompatibility(entries) {
  const summary = Object.fromEntries(
    Object.values(ROUTING_COMPATIBILITY_STATUS).map((status) => [status, 0]),
  );

  entries.forEach((entry) => {
    summary[entry.status] = (summary[entry.status] || 0) + 1;
  });

  return summary;
}

function addOutboundRouteSelectionDiagnostics(groups, clientTargets, diagnostics) {
  groups
    .filter((group) => group.type === 'sendsms-user')
    .forEach((group) => {
      const forcedEntry = getEntry(group, 'forced-smsc');
      const defaultEntry = getEntry(group, 'default-smsc');
      const routeEntry = forcedEntry || defaultEntry;

      if (!routeEntry) {
        return;
      }

      const username = getEntryValue(group, 'username');
      const target = clientTargets.get(normalizeRouteTarget(routeEntry.value));
      if (!username || hasUnsafeRoutingValue(username) || !target) {
        return;
      }

      diagnostics.push({
        severity: 'warning',
        source: routeEntry.source,
        line: routeEntry.line,
        group: group.type,
        key: routeEntry.key,
        message: `Generated Kannel ${routeEntry.key} routes match Sendium owner_id; review callers that send the HTTP account parameter because it can change the routed owner value.`,
      });

      if (defaultEntry) {
        diagnostics.push({
          severity: 'warning',
          source: defaultEntry.source,
          line: defaultEntry.line,
          group: group.type,
          key: defaultEntry.key,
          message: 'Kannel `default-smsc` can be overridden by request-level `smsc`; review generated owner and message_center route ordering before production use.',
        });
      }

      if (forcedEntry && defaultEntry) {
        diagnostics.push({
          severity: 'warning',
          source: defaultEntry.source,
          line: defaultEntry.line,
          group: group.type,
          key: defaultEntry.key,
          message: 'Kannel `forced-smsc` is stricter than `default-smsc`; the beta converter routes this user through the forced target and leaves default behavior for manual review.',
        });
      }
    });

  groups
    .filter((group) => group.type === 'smsc')
    .forEach((group) => {
      const sourceId = getEntryValue(group, 'smsc-id') || getEntryValue(group, 'id') || getEntryValue(group, 'host');
      const target = clientTargets.get(normalizeRouteTarget(sourceId));
      if (!target) {
        return;
      }

      [getEntry(group, 'preferred-smsc-id'), getEntry(group, 'allowed-smsc-id')]
        .filter(Boolean)
        .forEach((entry) => {
          if (!splitCsv(entry.value).some((selector) => !hasUnsafeRoutingValue(selector))) {
            return;
          }

          diagnostics.push({
            severity: 'warning',
            source: entry.source,
            line: entry.line,
            group: group.type,
            key: entry.key,
            message: `Mapped Kannel ${entry.key} as Sendium message_center route-selection logic only; verify request-level smsc precedence, fallback behavior, and any legacy allow/preference policy.`,
          });
        });
    });
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
        message: ROUTING_ADJACENT_GROUP_MESSAGES.get(group.type) || `Kannel \`group = ${group.type}\` is not converted by the current converter.`,
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

function buildRoutingModel(groups, smppboxGroups, clients, smppServers, diagnostics) {
  const model = {
    dlrTarget: smppServers[0] ? `smppserver.${smppServers[0].instanceName}` : '# smppserver.<instance>',
    fallbackTarget: clients[0]?.instanceName || '<manual-smpp-client>',
    messageRules: [],
  };

  const clientTargets = buildClientRouteTargetMap(clients);
  addUserSmscRoutes(groups, clientTargets, model, diagnostics);
  addSmppboxRouteToSmscRoutes(smppboxGroups, clientTargets, model, diagnostics);
  addSmscConstraintRoutes(groups, clientTargets, model, diagnostics);

  return model;
}

function buildClientRouteTargetMap(clients) {
  const targets = new Map();

  clients.forEach((client) => {
    addClientRouteTarget(targets, client.sourceId, client.instanceName);
    addClientRouteTarget(targets, client.instanceName, client.instanceName);
  });

  return targets;
}

function addClientRouteTarget(targets, sourceValue, instanceName) {
  if (!sourceValue) {
    return;
  }

  const key = normalizeRouteTarget(sourceValue);
  if (!targets.has(key)) {
    targets.set(key, instanceName);
  }
}

function addUserSmscRoutes(groups, clientTargets, model, diagnostics) {
  groups
    .filter((group) => group.type === 'sendsms-user')
    .forEach((group) => {
      const forcedEntry = getEntry(group, 'forced-smsc');
      const defaultEntry = getEntry(group, 'default-smsc');
      const routeEntry = forcedEntry || defaultEntry;
      const prefixContext = getPrefixContext(group);

      if (!routeEntry) {
        warnPrefixWithoutTarget(group, prefixContext, diagnostics);
        return;
      }

      const username = getEntryValue(group, 'username');
      if (!username) {
        diagnostics.push({
          severity: 'warning',
          source: routeEntry.source,
          line: routeEntry.line,
          group: group.type,
          key: routeEntry.key,
          message: `Could not map Kannel \`${routeEntry.key}\` because the sendsms-user has no username for Sendium \`owner_id\` routing.`,
        });
        return;
      }

      if (hasUnsafeRoutingValue(username)) {
        diagnostics.push({
          severity: 'warning',
          source: routeEntry.source,
          line: routeEntry.line,
          group: group.type,
          key: routeEntry.key,
          message: `Could not map Kannel \`${routeEntry.key}\` for \`${username}\` because Sendium routing values cannot contain colon or multi-rule separators safely.`,
        });
        return;
      }

      const target = clientTargets.get(normalizeRouteTarget(routeEntry.value));
      if (!target) {
        diagnostics.push({
          severity: 'warning',
          source: routeEntry.source,
          line: routeEntry.line,
          group: group.type,
          key: routeEntry.key,
          message: `Could not map Kannel \`${routeEntry.key} = ${routeEntry.value}\` because it does not match a converted SMPP client.`,
        });
        return;
      }

      addTargetedPrefixWarning(group, prefixContext, diagnostics);
      addAccountTargetRules(model, {
        target,
        accountId: username,
        routeLabel: `sendsms-user ${username} ${routeEntry.key}=${routeEntry.value}`,
        prefixContext,
      }, diagnostics);
    });
}

function addSmppboxRouteToSmscRoutes(groups, clientTargets, model, diagnostics) {
  groups
    .filter((group) => ['smppbox', 'opensmppbox', 'smppbox-user'].includes(group.type))
    .forEach((group) => {
      const routeEntry = getEntry(group, 'route-to-smsc');
      const prefixContext = getPrefixContext(group);

      if (!routeEntry) {
        warnPrefixWithoutTarget(group, prefixContext, diagnostics);
        return;
      }

      const accountId = getFirstEntryValue(group, ['system-id', 'systemid', 'system_id', 'username', 'user']);
      if (!accountId) {
        diagnostics.push({
          severity: 'warning',
          source: routeEntry.source,
          line: routeEntry.line,
          group: group.type,
          key: routeEntry.key,
          message: `Could not map Kannel \`${routeEntry.key}\` because the smppbox route has no system-id/account value for Sendium \`owner_id\` routing.`,
        });
        return;
      }

      if (hasUnsafeRoutingValue(accountId)) {
        diagnostics.push({
          severity: 'warning',
          source: routeEntry.source,
          line: routeEntry.line,
          group: group.type,
          key: routeEntry.key,
          message: `Could not map Kannel \`${routeEntry.key}\` for \`${accountId}\` because Sendium routing values cannot contain colon or multi-rule separators safely.`,
        });
        return;
      }

      const target = clientTargets.get(normalizeRouteTarget(routeEntry.value));
      if (!target) {
        diagnostics.push({
          severity: 'warning',
          source: routeEntry.source,
          line: routeEntry.line,
          group: group.type,
          key: routeEntry.key,
          message: `Could not map Kannel \`${routeEntry.key} = ${routeEntry.value}\` because it does not match a converted SMPP client.`,
        });
        return;
      }

      addTargetedPrefixWarning(group, prefixContext, diagnostics);
      addAccountTargetRules(model, {
        target,
        accountId,
        routeLabel: `${group.type} ${accountId} ${routeEntry.key}=${routeEntry.value}`,
        prefixContext,
      }, diagnostics);
    });
}

function addSmscConstraintRoutes(groups, clientTargets, model, diagnostics) {
  groups
    .filter((group) => group.type === 'smsc')
    .forEach((group) => {
      const preferredEntry = getEntry(group, 'preferred-smsc-id');
      const allowedEntry = getEntry(group, 'allowed-smsc-id');

      if (!preferredEntry && !allowedEntry) {
        return;
      }

      const sourceId = getEntryValue(group, 'smsc-id') || getEntryValue(group, 'id') || getEntryValue(group, 'host');
      const target = clientTargets.get(normalizeRouteTarget(sourceId));

      if (!target) {
        [preferredEntry, allowedEntry].filter(Boolean).forEach((entry) => {
          diagnostics.push({
            severity: 'warning',
            source: entry.source,
            line: entry.line,
            group: group.type,
            key: entry.key,
            message: `Could not map Kannel \`${entry.key}\` because the owning SMSC did not become a converted SMPP client.`,
          });
        });
        return;
      }

      const emittedSelectors = new Set();
      addSmscSelectorRules(model, diagnostics, {
        target,
        entry: preferredEntry,
        selectors: splitCsv(preferredEntry?.value || ''),
        emittedSelectors,
        commentLabel: 'preferred-smsc-id',
      });
      addSmscSelectorRules(model, diagnostics, {
        target,
        entry: allowedEntry,
        selectors: splitCsv(allowedEntry?.value || ''),
        emittedSelectors,
        commentLabel: 'allowed-smsc-id',
      });
    });
}

function addSmscSelectorRules(model, diagnostics, { target, entry, selectors, emittedSelectors, commentLabel }) {
  if (!entry) {
    return;
  }

  selectors.forEach((selector) => {
    const normalizedSelector = normalizeRouteTarget(selector);
    if (emittedSelectors.has(normalizedSelector)) {
      return;
    }

    if (hasUnsafeRoutingValue(selector)) {
      diagnostics.push({
        severity: 'warning',
        source: entry.source,
        line: entry.line,
        group: 'smsc',
        key: entry.key,
        message: `Could not map Kannel \`${entry.key} = ${selector}\` because Sendium routing values cannot contain colon or multi-rule separators safely.`,
      });
      return;
    }

    emittedSelectors.add(normalizedSelector);
    model.messageRules.push({
      target,
      conditions: [{ field: 'message_center', operator: 'equals', value: selector }],
      comment: `Converted from SMSC ${target} ${commentLabel}=${selector}`,
    });
  });
}

function addAccountTargetRules(model, { target, accountId, routeLabel, prefixContext }, diagnostics) {
  const baseCondition = { field: 'owner_id', operator: 'equals', value: accountId };
  const deniedConditions = buildDeniedPrefixConditions(prefixContext, diagnostics);

  if (prefixContext.allowed.length > 0) {
    prefixContext.allowed.forEach((prefix) => {
      if (hasUnsafeRoutingValue(prefix)) {
        warnUnsafePrefix(prefixContext.allowedEntry, prefix, diagnostics);
        return;
      }

      model.messageRules.push({
        target,
        conditions: [baseCondition, { field: 'to', operator: 'startsWith', value: prefix }, ...deniedConditions],
        comment: `Converted from ${routeLabel} allowed-prefix=${prefix}`,
      });
    });
    return;
  }

  model.messageRules.push({
    target,
    conditions: [baseCondition, ...deniedConditions],
    comment: `Converted from ${routeLabel}`,
  });
}

function buildDeniedPrefixConditions(prefixContext, diagnostics) {
  return prefixContext.denied
    .filter((prefix) => {
      if (!hasUnsafeRoutingValue(prefix)) {
        return true;
      }

      warnUnsafePrefix(prefixContext.deniedEntry, prefix, diagnostics);
      return false;
    })
    .map((prefix) => ({ field: 'to', operator: '!startsWith', value: prefix }));
}

function getPrefixContext(group) {
  const allowedEntry = getEntry(group, 'allowed-prefix');
  const deniedEntry = getEntry(group, 'denied-prefix');

  return {
    allowedEntry,
    deniedEntry,
    allowed: splitCsv(allowedEntry?.value || ''),
    denied: splitCsv(deniedEntry?.value || ''),
  };
}

function warnPrefixWithoutTarget(group, prefixContext, diagnostics) {
  [prefixContext.allowedEntry, prefixContext.deniedEntry].filter(Boolean).forEach((entry) => {
    diagnostics.push({
      severity: 'warning',
      source: entry.source,
      line: entry.line,
      group: group.type,
      key: entry.key,
      message: `Could not map Kannel \`${entry.key}\` because no resolvable SMSC route target was found in the same group.`,
    });
  });
}

function addTargetedPrefixWarning(group, prefixContext, diagnostics) {
  [prefixContext.allowedEntry, prefixContext.deniedEntry].filter(Boolean).forEach((entry) => {
    diagnostics.push({
      severity: 'warning',
      source: entry.source,
      line: entry.line,
      group: group.type,
      key: entry.key,
      message: `Mapped Kannel \`${entry.key}\` as route-selection logic only; verify whether Sendium must also reject submissions outside the legacy prefix policy.`,
    });
  });
}

function warnUnsafePrefix(entry, prefix, diagnostics) {
  diagnostics.push({
    severity: 'warning',
    source: entry?.source,
    line: entry?.line,
    key: entry?.key,
    message: `Could not map Kannel prefix \`${prefix}\` because Sendium routing values cannot contain colon or multi-rule separators safely.`,
  });
}

function renderRoutingTable(routingModel) {
  const lines = [
    '# Routing starter generated by Sendium Kannel Converter',
    '# Review generated rules against legacy Kannel routing behavior before production use.',
    '[default]',
    'MESSAGE:type:==:0',
    'MESSAGE:type:==:11',
    'MESSAGE:type:==:14',
    'MESSAGE:type:==:17',
    'MESSAGE:type:==:10',
    `${routingModel.dlrTarget}:type:==:18`,
    '',
    '[MESSAGE]',
  ];

  routingModel.messageRules.forEach((rule) => {
    lines.push(`# ${rule.comment}`);
    lines.push(renderRoutingRule(rule));
  });

  lines.push(`${routingModel.fallbackTarget}::default:`);

  return lines.join('\n');
}

function renderRoutingRule(rule) {
  const conditions = rule.conditions || [{ field: rule.field, operator: rule.operator, value: rule.value }];

  if (conditions.length === 1) {
    const [condition] = conditions;
    return `${rule.target}:${condition.field}:${condition.operator}:${condition.value}`;
  }

  return [
    rule.target,
    conditions.map((condition) => condition.field).join('~~'),
    conditions.map((condition) => condition.operator).join('~~'),
    conditions.map((condition) => condition.value).join('~~'),
  ].join(':');
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
  return getEntry(group, key)?.value || '';
}

function getEntry(group, key) {
  return group.entries.find((entry) => entry.key === key);
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

function normalizeRouteTarget(value) {
  return value.trim().toLowerCase();
}

function hasUnsafeRoutingValue(value) {
  return value.includes(':') || value.includes('~~');
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
