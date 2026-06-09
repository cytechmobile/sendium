import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { convertKannelConfig, getRoutingCompatibilityMatrix } from '../src/converter.js';

const testDir = dirname(fileURLToPath(import.meta.url));

function fixture(name) {
  return readFileSync(join(testDir, 'fixtures', name), 'utf8');
}

function testBasicConversion() {
  const result = convertKannelConfig(fixture('basic.kannel.conf'));

  assert.equal(result.summary.groups, 4);
  assert.equal(result.summary.credentials, 1);
  assert.equal(result.summary.smppClients, 1);
  assert.equal(result.summary.errors, 0);

  assert.match(result.files['credentials.yml'], /systemId: "legacy-http-user"/);
  assert.match(result.files['credentials.yml'], /- "10\.0\.0\.15"/);
  assert.match(result.files['credentials.yml'], /- "10\.0\.0\.16"/);

  assert.match(result.files['smsg.properties'], /outSms\.instance\.upstreamA\.type = smppclient/);
  assert.match(result.files['smsg.properties'], /outSms\.instance\.upstreamA\.connections\.transceivers = 0/);
  assert.match(result.files['smsg.properties'], /outSms\.instance\.upstreamA\.connections\.transmitters = 1/);
  assert.match(result.files['smsg.properties'], /outSms\.instance\.upstreamA\.connections\.receivers = 1/);
  assert.match(result.files['smsg.properties'], /outSms\.instance\.upstreamA\.enquire\.link\.interval\.millis = 30000/);
  assert.match(result.files['smsg.properties'], /outSms\.instance\.upstreamA\.reconnect\.interval\.millis = 60000/);
  assert.match(result.files['smsg.properties'], /outSms\.instance\.upstreamA\.interfaceVersion = 52/);
  assert.match(result.files['smsg.properties'], /outSms\.instance\.upstreamA\.src\.addr\.ton = 5/);
  assert.match(result.files['smsg.properties'], /outSms\.instance\.upstreamA\.dest\.addr\.npi = 1/);
  assert.match(result.files['routingTable.conf'], /upstreamA:owner_id:equals:legacy-http-user/);
  assert.match(result.files['routingTable.conf'], /upstreamA:message_center:equals:upstream-a/);
  assert.match(result.files['routingTable.conf'], /upstreamA::default:/);

  assert.ok(
    result.diagnostics.some((diagnostic) => diagnostic.key === 'forced-smsc' && diagnostic.message.includes('HTTP account parameter')),
    'resolvable forced-smsc should warn about account override semantics',
  );
  assert.ok(
    result.diagnostics.some((diagnostic) => diagnostic.key === 'allowed-smsc-id' && diagnostic.message.includes('message_center route-selection')),
    'resolvable allowed-smsc-id should warn about request smsc selector semantics',
  );
}

function testUserDefaultSmscRouting() {
  const result = convertKannelConfig(`group = sendsms-user
username = account-a
password = secret
default-smsc = backup-a

group = sendsms-user
username = account-b
password = secret
forced-smsc = missing-a

group = smsc
smsc = smpp
smsc-id = primary-a
host = primary.example.test
port = 2775
smsc-username = primary-user
smsc-password = primary-pass

group = smsc
smsc = smpp
smsc-id = backup-a
host = backup.example.test
port = 2775
smsc-username = backup-user
smsc-password = backup-pass
`);

  assert.match(result.files['routingTable.conf'], /backupA:owner_id:equals:account-a/);
  assert.match(result.files['routingTable.conf'], /primaryA::default:/);

  assert.ok(
    result.diagnostics.some((diagnostic) => diagnostic.key === 'default-smsc' && diagnostic.message.includes('request-level `smsc`')),
    'default-smsc should warn about request-level smsc override semantics',
  );
  assert.ok(
    result.diagnostics.some((diagnostic) => diagnostic.key === 'default-smsc' && diagnostic.message.includes('HTTP account parameter')),
    'default-smsc should warn about owner_id account override semantics',
  );

  const unresolvedRoute = result.diagnostics.find((diagnostic) => diagnostic.key === 'forced-smsc');
  assert.match(unresolvedRoute?.message || '', /does not match a converted SMPP client/);
  assert.ok(
    unresolvedRoute?.references.some((reference) => reference.label === 'Sendium routing engine'),
    'unresolved user routing diagnostics should link to Sendium routing guidance',
  );
}

function testUserPrefixRouting() {
  const result = convertKannelConfig(`group = sendsms-user
username = account-a
password = secret
forced-smsc = backup-a
allowed-prefix = 3069, 3070
denied-prefix = 306955

group = smsc
smsc = smpp
smsc-id = primary-a
host = primary.example.test
port = 2775
smsc-username = primary-user
smsc-password = primary-pass

group = smsc
smsc = smpp
smsc-id = backup-a
host = backup.example.test
port = 2775
smsc-username = backup-user
smsc-password = backup-pass
`);

  assert.match(
    result.files['routingTable.conf'],
    /backupA:owner_id~~to~~to:equals~~startsWith~~!startsWith:account-a~~3069~~306955/,
  );
  assert.match(
    result.files['routingTable.conf'],
    /backupA:owner_id~~to~~to:equals~~startsWith~~!startsWith:account-a~~3070~~306955/,
  );
  assert.doesNotMatch(result.files['routingTable.conf'], /backupA:owner_id:equals:account-a/);

  const prefixDiagnostic = result.diagnostics.find((diagnostic) => diagnostic.key === 'allowed-prefix');
  assert.match(prefixDiagnostic?.message || '', /route-selection logic only/);
  assert.ok(
    prefixDiagnostic?.references.some((reference) => reference.label === 'Sendium routing engine'),
    'prefix diagnostics should link to Sendium routing guidance',
  );
}

function testSmscSelectorRouting() {
  const result = convertKannelConfig(`group = sendsms-user
username = account-a
password = secret

group = smsc
smsc = smpp
smsc-id = primary-a
host = primary.example.test
port = 2775
smsc-username = primary-user
smsc-password = primary-pass
preferred-smsc-id = vip-a
allowed-smsc-id = standard-a, vip-a
denied-smsc-id = blocked-a

group = smsc
smsc = smpp
smsc-id = backup-a
host = backup.example.test
port = 2775
smsc-username = backup-user
smsc-password = backup-pass
allowed-smsc-id = backup-a
`);

  const routing = result.files['routingTable.conf'];
  const preferredIndex = routing.indexOf('primaryA:message_center:equals:vip-a');
  const allowedIndex = routing.indexOf('primaryA:message_center:equals:standard-a');

  assert.notEqual(preferredIndex, -1, 'preferred-smsc-id should generate a message_center route');
  assert.notEqual(allowedIndex, -1, 'allowed-smsc-id should generate a message_center route');
  assert.ok(preferredIndex < allowedIndex, 'preferred SMSC selector routes should render before allowed selectors');
  assert.match(routing, /backupA:message_center:equals:backup-a/);
  assert.ok(
    result.diagnostics.some((diagnostic) => diagnostic.key === 'preferred-smsc-id' && diagnostic.message.includes('message_center route-selection')),
    'resolvable preferred-smsc-id should warn about request smsc selector semantics',
  );
  assert.ok(
    result.diagnostics.some((diagnostic) => diagnostic.key === 'allowed-smsc-id' && diagnostic.message.includes('message_center route-selection')),
    'resolvable allowed-smsc-id should warn about request smsc selector semantics',
  );
  assert.ok(
    result.diagnostics.some((diagnostic) => diagnostic.key === 'denied-smsc-id'),
    'denied-smsc-id should remain a routing review diagnostic',
  );
}

function testUnsupportedAndMissingRequiredValues() {
  const result = convertKannelConfig(fixture('unsupported.kannel.conf'));

  assert.equal(result.summary.credentials, 0);
  assert.equal(result.summary.smppClients, 0);
  assert.ok(result.summary.errors >= 1);

  assert.ok(
    result.diagnostics.some((diagnostic) => diagnostic.message.includes('Only SMPP SMSC blocks are converted')),
    'non-SMPP SMSC should be flagged',
  );
  assert.ok(
    result.diagnostics.some((diagnostic) => diagnostic.key === 'unknown-provider-setting'),
    'unknown SMSC key should be flagged',
  );
  assert.ok(
    result.diagnostics.some((diagnostic) => diagnostic.message.includes('require both username and password')),
    'incomplete HTTP credentials should be an error',
  );
  assert.ok(
    result.diagnostics.some((diagnostic) => diagnostic.group === 'wapbox'),
    'unsupported group should be flagged',
  );
}

function testParserKeepsInlineCommentInsideQuotes() {
  const result = convertKannelConfig(`group = sendsms-user
username = "user#1"
password = "pass;word" # real comment
`);

  assert.equal(result.summary.credentials, 1);
  assert.match(result.files['credentials.yml'], /systemId: "user#1"/);
  assert.match(result.files['credentials.yml'], /password: "pass;word"/);
}

function testSmppboxIngressConversion() {
  const result = convertKannelConfig(fixture('basic.kannel.conf'), fixture('smppbox.conf'));

  assert.equal(result.summary.smppServers, 1);
  assert.match(result.files['credentials.yml'], /type: SMPP/);
  assert.match(result.files['credentials.yml'], /systemId: "downstream-user"/);
  assert.match(result.files['smsg.properties'], /outSms\.instance\.smppIngress\.type = smppserver/);
  assert.match(result.files['smsg.properties'], /outSms\.instance\.smppIngress\.srv\.port = 2775/);
  assert.match(result.files['smsg.properties'], /outSms\.instance\.smppIngress\.srv\.maxConnections = 100/);
  assert.match(result.files['smsg.properties'], /outSms\.instance\.smppIngress\.srv\.maxConnectionsPerIP = 4/);
  assert.match(result.files['smsg.properties'], /outSms\.instance\.smppIngress\.conf\.maxRate\.default = 20/);
  assert.match(result.files['smsg.properties'], /outSms\.instance\.smppIngress\.conf\.maxPending\.default = 500/);
  assert.match(result.files['routingTable.conf'], /smppserver\.smppIngress:type:==:18/);
  assert.match(
    result.files['routingTable.conf'],
    /upstreamA:owner_id~~to:equals~~startsWith:downstream-user~~447/,
  );
  assert.equal(
    result.diagnostics.some((diagnostic) => diagnostic.key === 'route-to-smsc'),
    false,
    'resolvable smppbox route-to-smsc should become an active routing rule',
  );

  const bearerboxDiagnostic = result.diagnostics.find((diagnostic) => diagnostic.key === 'bearerbox-host');
  assert.equal(bearerboxDiagnostic?.source, 'smppbox.conf');
  assert.ok(
    bearerboxDiagnostic?.references.some((reference) => reference.label === 'Sendium SMPP configuration'),
    'smppbox diagnostics should link to Sendium SMPP guidance',
  );
}

function testRoutingCompatibilityMatrixModel() {
  const matrix = getRoutingCompatibilityMatrix();
  const forcedSmsc = matrix.find((entry) => entry.id === 'sendsms-user.forced-smsc');
  const deniedSmsc = matrix.find((entry) => entry.id === 'smsc.denied-smsc-id');
  const smsService = matrix.find((entry) => entry.id === 'sms-service.group');

  assert.equal(forcedSmsc?.status, 'mapped-active');
  assert.equal(forcedSmsc?.activeRouteGeneration, true);
  assert.ok(forcedSmsc?.sendiumSurfaces.includes('routingTable.conf'));
  assert.equal(deniedSmsc?.status, 'warning-only');
  assert.equal(deniedSmsc?.activeRouteGeneration, false);
  assert.equal(smsService?.status, 'runtime-needed');
}

function testRoutingCompatibilityReportKeepsSourceMetadata() {
  const result = convertKannelConfig(`group = sendsms-user
username = account-a
password = secret
forced-smsc = upstream-a
denied-prefix = 306955

group = smsc
smsc = smpp
smsc-id = upstream-a
host = smpp.example.test
port = 2775
smsc-username = upstream-user
smsc-password = upstream-pass
denied-smsc-id = blocked-a

group = sms-service
keyword = balance
`);

  const forcedSmsc = result.routingCompatibility.entries.find((entry) => entry.id === 'sendsms-user.forced-smsc');
  const deniedSmsc = result.routingCompatibility.entries.find((entry) => entry.id === 'smsc.denied-smsc-id');
  const smsService = result.routingCompatibility.entries.find((entry) => entry.id === 'sms-service.group');

  assert.equal(forcedSmsc?.source, 'kannel.conf');
  assert.equal(forcedSmsc?.line, 4);
  assert.equal(forcedSmsc?.status, 'mapped-active');
  assert.equal(deniedSmsc?.status, 'warning-only');
  assert.equal(deniedSmsc?.activeRouteGeneration, false);
  assert.equal(smsService?.line, 16);
  assert.equal(result.routingCompatibility.summary['mapped-active'], 2);
  assert.equal(result.routingCompatibility.summary['warning-only'], 1);
  assert.equal(result.routingCompatibility.summary['runtime-needed'], 1);
  assert.doesNotMatch(result.files['routingTable.conf'], /blocked-a/);
}

function testRoutingAdjacentGroupDiagnostics() {
  const result = convertKannelConfig(`group = smsbox-route
smsbox-id = smsbox-a
smsc-id = upstream-a

group = smsc-route
smsc-id = upstream-a
prefix = 3069
account = account-a

group = sms-service
keyword = balance
get-url = https://legacy.example.test/mo
accepted-smsc = upstream-a
`);

  const smsboxRoute = result.diagnostics.find((diagnostic) => diagnostic.group === 'smsbox-route' && !diagnostic.key);
  const smscRoute = result.diagnostics.find((diagnostic) => diagnostic.group === 'smsc-route' && !diagnostic.key);
  const smsService = result.diagnostics.find((diagnostic) => diagnostic.group === 'sms-service' && !diagnostic.key);
  const keyword = result.diagnostics.find((diagnostic) => diagnostic.group === 'sms-service' && diagnostic.key === 'keyword');
  const routePrefix = result.diagnostics.find((diagnostic) => diagnostic.group === 'smsc-route' && diagnostic.key === 'prefix');

  assert.match(smsboxRoute?.message || '', /smsbox routing topology/);
  assert.match(smscRoute?.message || '', /explicit SMSC route topology/);
  assert.match(smsService?.message || '', /MO\/keyword service behavior/);
  assert.match(keyword?.message || '', /MO webhook or application logic/);
  assert.match(routePrefix?.message || '', /warning-only/);
  assert.ok(
    keyword?.references.some((reference) => reference.label === 'Sendium webhooks'),
    'sms-service diagnostics should link to Sendium webhook guidance',
  );
  assert.equal(
    result.routingCompatibility.entries.some((entry) => entry.group === 'smsc-route' && entry.status === 'unsupported'),
    true,
  );
  assert.equal(
    result.routingCompatibility.entries.some((entry) => entry.group === 'sms-service' && entry.status === 'runtime-needed'),
    true,
  );
  assert.doesNotMatch(result.files['routingTable.conf'], /3069/);
  assert.doesNotMatch(result.files['routingTable.conf'], /balance/);
}

function testRoutingHeavyFixtureDiagnostics() {
  const result = convertKannelConfig(fixture('routing-heavy.kannel.conf'), fixture('routing-heavy.smppbox.conf'));

  assert.equal(result.summary.groups, 9);
  assert.equal(result.summary.credentials, 4);
  assert.equal(result.summary.smppClients, 2);
  assert.equal(result.summary.smppServers, 1);

  const routing = result.files['routingTable.conf'];
  assert.match(routing, /backupA:owner_id~~to~~to:equals~~startsWith~~!startsWith:account-a~~3069~~306955/);
  assert.match(routing, /primaryA:owner_id:equals:account-b/);
  assert.match(routing, /primaryA:message_center:equals:vip-a/);
  assert.match(routing, /backupA:message_center:equals:backup-a/);
  assert.match(routing, /primaryA:owner_id~~to~~to:equals~~startsWith~~!startsWith:downstream-a~~3069~~306955/);
  assert.doesNotMatch(routing, /downstream-b/);
  assert.doesNotMatch(routing, /balance/);
  assert.doesNotMatch(routing, /legacy\.example\.test/);

  assert.equal(result.routingCompatibility.summary['mapped-active'], 10);
  assert.equal(result.routingCompatibility.summary['warning-only'], 3);
  assert.equal(result.routingCompatibility.summary.unsupported, 2);
  assert.equal(result.routingCompatibility.summary['runtime-needed'], 1);

  assert.ok(
    result.diagnostics.some((diagnostic) => diagnostic.key === 'default-smsc' && diagnostic.message.includes('request-level `smsc`')),
    'fixture default-smsc should warn about request-level smsc overrides',
  );
  assert.ok(
    result.diagnostics.some((diagnostic) => diagnostic.key === 'default-smsc' && diagnostic.routingStatus === 'mapped-active'),
    'fixture generated default-smsc diagnostic should carry mapped-active routing status',
  );
  assert.ok(
    result.diagnostics.some((diagnostic) => diagnostic.key === 'default-smsc' && diagnostic.routingStatus === 'warning-only'),
    'fixture forced/default conflict should downgrade default-smsc status to warning-only',
  );
  assert.ok(
    result.diagnostics.some((diagnostic) => diagnostic.key === 'route-to-smsc' && diagnostic.message.includes('does not match a converted SMPP client') && diagnostic.routingStatus === 'warning-only'),
    'fixture unresolved smppbox route target should warn without generating a route',
  );
  assert.ok(
    result.diagnostics.some((diagnostic) => diagnostic.group === 'sms-service' && diagnostic.key === 'get-url' && diagnostic.line === 45 && diagnostic.routingStatus === 'runtime-needed'),
    'fixture sms-service get-url diagnostic should keep source line metadata',
  );
  assert.ok(
    result.diagnostics.some((diagnostic) => diagnostic.group === 'smsc-route' && diagnostic.key === 'prefix' && diagnostic.line === 40 && diagnostic.routingStatus === 'unsupported'),
    'fixture smsc-route prefix diagnostic should keep source line metadata',
  );
}

testBasicConversion();
testUserDefaultSmscRouting();
testUserPrefixRouting();
testSmscSelectorRouting();
testUnsupportedAndMissingRequiredValues();
testParserKeepsInlineCommentInsideQuotes();
testSmppboxIngressConversion();
testRoutingCompatibilityMatrixModel();
testRoutingCompatibilityReportKeepsSourceMetadata();
testRoutingAdjacentGroupDiagnostics();
testRoutingHeavyFixtureDiagnostics();

console.log('converter tests passed');
