import { readFile, mkdir, writeFile } from "node:fs/promises";
import { basename, dirname, resolve } from "node:path";
import os from "node:os";

function option(name, fallback) {
  const prefix = `--${name}=`;
  const match = process.argv.slice(2).find((value) => value.startsWith(prefix));
  return match ? match.slice(prefix.length) : fallback;
}

const baseUrl = option("base-url", "http://127.0.0.1:8000").replace(/\/$/, "");
const datasetPath = resolve(option("dataset", "datasets/official/lct-2026.geojson"));
const users = Number(option("users", "50"));
const queueRuns = option("queue-runs", "false") === "true";
const timeoutMs = Number(option("timeout-ms", String(3 * 60 * 60 * 1000)));
const outputPath = resolve(option("output", `artifacts/r9-load-${Date.now()}.json`));
if (!Number.isInteger(users) || users < 1 || users > 500) {
  throw new Error("--users must be an integer from 1 to 500");
}

const dataset = await readFile(datasetPath);
const startedAt = new Date();

async function jsonRequest(url, init) {
  const started = performance.now();
  const response = await fetch(url, { ...init, signal: AbortSignal.timeout(timeoutMs) });
  const text = await response.text();
  let body;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = { raw: text.slice(0, 500) };
  }
  if (!response.ok) {
    throw new Error(`${init?.method ?? "GET"} ${url} -> ${response.status}: ${text.slice(0, 500)}`);
  }
  return { status: response.status, body, durationMs: performance.now() - started };
}

const uploads = await Promise.all(Array.from({ length: users }, async (_, index) => {
  const form = new FormData();
  form.append("file", new Blob([dataset], { type: "application/geo+json" }), `${index}-${basename(datasetPath)}`);
  return jsonRequest(`${baseUrl}/api/v1/official/imports`, { method: "POST", body: form });
}));
const importIds = new Set(uploads.map(({ body }) => body.id));
if (importIds.size !== 1) {
  throw new Error(`Deduplication failed: ${importIds.size} import IDs returned for identical bytes`);
}

let runs = [];
if (queueRuns) {
  const importId = uploads[0].body.id;
  runs = await Promise.all(Array.from({ length: users }, () =>
    jsonRequest(`${baseUrl}/api/v1/official/imports/${encodeURIComponent(importId)}/runs`, { method: "POST" })));
  const pending = new Map(runs.map(({ body }) => [body.id, body.state]));
  const deadline = Date.now() + timeoutMs;
  while (pending.size > 0 && Date.now() < deadline) {
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 1000));
    await Promise.all([...pending.keys()].map(async (runId) => {
      const current = await jsonRequest(`${baseUrl}/api/v1/official/runs/${encodeURIComponent(runId)}`);
      if (["completed", "failed", "cancelled"].includes(current.body.state)) {
        if (current.body.state !== "completed") {
          throw new Error(`Run ${runId} finished as ${current.body.state}: ${current.body.error_code ?? "unknown"}`);
        }
        pending.delete(runId);
      }
    }));
  }
  if (pending.size > 0) {
    throw new Error(`${pending.size} runs did not finish before the timeout`);
  }
}

const durations = uploads.map(({ durationMs }) => durationMs).sort((left, right) => left - right);
const percentile = (value) => durations[Math.min(durations.length - 1, Math.floor(durations.length * value))];
const evidence = {
  started_at: startedAt.toISOString(),
  completed_at: new Date().toISOString(),
  base_url: baseUrl,
  users,
  queue_runs: queueRuns,
  dataset: { path: datasetPath, bytes: dataset.length },
  deduplicated_import_id: uploads[0].body.id,
  upload_latency_ms: {
    min: durations[0],
    p50: percentile(0.5),
    p95: percentile(0.95),
    max: durations[durations.length - 1],
  },
  queued_runs: runs.length,
  client: {
    node: process.version,
    platform: process.platform,
    cpu_count: os.cpus().length,
    total_memory_bytes: os.totalmem(),
  },
};
await mkdir(dirname(outputPath), { recursive: true });
await writeFile(outputPath, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");
console.log(JSON.stringify({ evidence: outputPath, ...evidence }, null, 2));
