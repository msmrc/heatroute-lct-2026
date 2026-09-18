import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { once } from "node:events";
import test from "node:test";

import { createLocalDemoServer, extractMultipartFile } from "./local-demo-server.mjs";

const officialBytes = Buffer.from('{"type":"FeatureCollection","features":[]}');
const officialSha256 = createHash("sha256").update(officialBytes).digest("hex");

function fixtureBundle() {
  return {
    import: {
      id: "local-demo",
      state: "valid",
      original_filename: "lct-2026.geojson",
      input_size_bytes: officialBytes.length,
      report: { valid: true, feature_count: 0, errors: [], warnings: [], feature_counts: {} },
    },
    run: {
      id: "local-demo-run",
      import_id: "local-demo",
      state: "completed",
      input_sha256: officialSha256,
      result: { variants: [], preferred_variant_id: "" },
    },
    map: { type: "FeatureCollection", features: [], truncated: false },
  };
}

async function withServer(t) {
  const server = createLocalDemoServer({ bundle: fixtureBundle() });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  t.after(() => server.close());
  const address = server.address();
  assert.notEqual(address, null);
  assert.equal(typeof address, "object");
  return `http://127.0.0.1:${address.port}`;
}

test("extractMultipartFile returns the named file bytes", async () => {
  const body = new FormData();
  body.set("file", new Blob([officialBytes], { type: "application/geo+json" }), "official.geojson");
  const request = new Request("http://localhost", { method: "POST", body });
  const parsed = extractMultipartFile(
    Buffer.from(await request.arrayBuffer()),
    request.headers.get("content-type") ?? "",
  );

  assert.equal(parsed?.filename, "official.geojson");
  assert.deepEqual(parsed?.bytes, officialBytes);
});

test("exact official upload replays the completed calculation flow", async (t) => {
  const baseUrl = await withServer(t);
  const body = new FormData();
  body.set("file", new Blob([officialBytes], { type: "application/geo+json" }), "uploaded.geojson");

  const importedResponse = await fetch(`${baseUrl}/api/v1/official/imports`, { method: "POST", body });
  assert.equal(importedResponse.status, 200);
  const imported = await importedResponse.json();
  assert.equal(imported.id, "local-demo");
  assert.equal(imported.original_filename, "uploaded.geojson");

  const runResponse = await fetch(`${baseUrl}/api/v1/official/imports/local-demo/runs`, { method: "POST" });
  assert.equal(runResponse.status, 200);
  const run = await runResponse.json();
  assert.equal(run.id, "local-demo-run");
  assert.equal(run.state, "completed");

  const persistedImport = await fetch(`${baseUrl}/api/v1/official/imports/local-demo`).then((response) => response.json());
  assert.equal(persistedImport.original_filename, "uploaded.geojson");
});

test("a different file is rejected instead of showing an unrelated result", async (t) => {
  const baseUrl = await withServer(t);
  const body = new FormData();
  body.set("file", new Blob(["not the official dataset"], { type: "application/geo+json" }), "other.geojson");

  const response = await fetch(`${baseUrl}/api/v1/official/imports`, { method: "POST", body });
  assert.equal(response.status, 422);
  assert.match((await response.json()).message, /не содержит результата для этого GeoJSON/);
});

test("multipart payloads beyond the replay boundary are rejected early", async (t) => {
  const baseUrl = await withServer(t);
  const response = await fetch(`${baseUrl}/api/v1/official/imports`, {
    method: "POST",
    headers: { "content-type": "multipart/form-data; boundary=test" },
    body: Buffer.alloc(1024 * 1024 + officialBytes.length + 1),
  });

  assert.equal(response.status, 413);
});
