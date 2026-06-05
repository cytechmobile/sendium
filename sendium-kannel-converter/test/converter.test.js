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
  assert.match(result.files['routingTable.conf'], /upstreamA::default:/);

  assert.ok(
    result.diagnostics.some((diagnostic) => diagnostic.key === 'forced-smsc'),
    'forced-smsc should be flagged for routing review',
  );
  assert.ok(
    result.diagnostics
      .find((diagnostic) => diagnostic.key === 'forced-smsc')
      ?.references.some((reference) => reference.label === 'Sendium routing engine'),
    'forced-smsc should link to Sendium routing guidance',
  );
  assert.ok(
    result.diagnostics.some((diagnostic) => diagnostic.key === 'allowed-smsc-id'),
    'allowed-smsc-id should be flagged for routing review',
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

  const bearerboxDiagnostic = result.diagnostics.find((diagnostic) => diagnostic.key === 'bearerbox-host');
  assert.equal(bearerboxDiagnostic?.source, 'smppbox.conf');
  assert.ok(
    bearerboxDiagnostic?.references.some((reference) => reference.label === 'Sendium SMPP configuration'),
    'smppbox diagnostics should link to Sendium SMPP guidance',
  );
}

testBasicConversion();
testUnsupportedAndMissingRequiredValues();
testParserKeepsInlineCommentInsideQuotes();
testSmppboxIngressConversion();

console.log('converter tests passed');
