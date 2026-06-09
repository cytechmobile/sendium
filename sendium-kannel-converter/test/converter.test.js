import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { convertKannelConfig } from '../src/converter.js';

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

  assert.equal(
    result.diagnostics.some((diagnostic) => diagnostic.key === 'forced-smsc'),
    false,
    'resolvable forced-smsc should become an active routing rule',
  );
  assert.equal(
    result.diagnostics.some((diagnostic) => diagnostic.key === 'allowed-smsc-id'),
    false,
    'resolvable allowed-smsc-id should become an active routing rule',
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
  assert.equal(
    result.diagnostics.some((diagnostic) => diagnostic.key === 'preferred-smsc-id'),
    false,
    'resolvable preferred-smsc-id should become an active routing rule',
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

testBasicConversion();
testUserDefaultSmscRouting();
testUserPrefixRouting();
testSmscSelectorRouting();
testUnsupportedAndMissingRequiredValues();
testParserKeepsInlineCommentInsideQuotes();
testSmppboxIngressConversion();

console.log('converter tests passed');
