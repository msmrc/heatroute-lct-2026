import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const officialUrl = new URL('../datasets/official/lct-2026.geojson', import.meta.url);
const scenarioUrl = new URL('../datasets/scenarios/roads-kindergarten.geojson', import.meta.url);

test('roads/kindergarten scenario preserves the supplied bytes', async () => {
  const bytes = await readFile(scenarioUrl);
  assert.equal(bytes.length, 307424);
  assert.equal(createHash('sha256').update(bytes).digest('hex'),
    'acac7a6885f53faa360becde85bcfea6571eb01c60bcf106da7b2c378918125b');
});

test('all official features are unchanged and only explicit restrictions are added', async () => {
  const official = JSON.parse(await readFile(officialUrl, 'utf8'));
  const scenario = JSON.parse(await readFile(scenarioUrl, 'utf8'));
  assert.equal(scenario.type, 'FeatureCollection');
  assert.equal(official.features.length, 144);
  assert.equal(scenario.features.length, 239);
  const key = feature => `${feature.properties.object_type}:${String(feature.properties.id)}`;
  const actual = new Map(scenario.features.map(feature => [key(feature), feature]));
  assert.equal(actual.size, scenario.features.length, 'no duplicate typed source IDs');
  for (const feature of official.features) {
    assert.deepEqual(actual.get(key(feature)), feature, key(feature));
    actual.delete(key(feature));
  }
  const added = [...actual.values()];
  assert.equal(added.length, 95);
  assert(added.every(feature => feature.properties.object_type === 'restriction'
    && feature.properties.experimental === true));
  assert.equal(added.filter(feature => feature.properties.restriction_type === 'road').length, 94);
  assert.equal(added.filter(feature => feature.properties.restriction_type === 'social_area').length, 1);
  assert.equal(scenario.features.filter(feature => feature.properties.object_type === 'oks_connection_point').length, 17);
});
