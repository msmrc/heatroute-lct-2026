import { createServer } from "node:http";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const contractFiles = new Map([
  ["input", "lct-2026-input.schema.json"],
  ["provided-dataset", "lct-2026-provided-dataset.schema.json"],
  ["output", "lct-2026-output.schema.json"],
]);

function send(response, status, payload) {
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
  });
  response.end(JSON.stringify(payload));
}

function sendSchema(response, payload) {
  response.writeHead(200, {
    "content-type": "application/schema+json; charset=utf-8",
    "cache-control": "public, max-age=31536000",
  });
  response.end(payload);
}

function requestBody(request, maximumBytes) {
  return new Promise((resolveBody, rejectBody) => {
    const chunks = [];
    let size = 0;
    let settled = false;

    request.on("data", (chunk) => {
      if (settled) return;
      size += chunk.length;
      if (size > maximumBytes) {
        settled = true;
        rejectBody(Object.assign(new Error("PAYLOAD_TOO_LARGE"), { code: "PAYLOAD_TOO_LARGE" }));
        return;
      }
      chunks.push(chunk);
    });
    request.on("end", () => {
      if (!settled) resolveBody(Buffer.concat(chunks));
    });
    request.on("error", (error) => {
      if (!settled) rejectBody(error);
    });
  });
}

export function extractMultipartFile(body, contentType = "") {
  const boundaryMatch = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType);
  const boundary = boundaryMatch?.[1] ?? boundaryMatch?.[2]?.trim();
  if (!boundary) return undefined;

  const headerMarker = Buffer.from("\r\n\r\n");
  const nextBoundary = Buffer.from(`\r\n--${boundary}`);
  let cursor = 0;
  while (cursor < body.length) {
    const headerEnd = body.indexOf(headerMarker, cursor);
    if (headerEnd < 0) return undefined;
    const headerStart = body.lastIndexOf(Buffer.from(`--${boundary}`), headerEnd);
    if (headerStart < 0) return undefined;
    const headers = body.subarray(headerStart, headerEnd).toString("utf8");
    const dataStart = headerEnd + headerMarker.length;
    const dataEnd = body.indexOf(nextBoundary, dataStart);
    if (dataEnd < 0) return undefined;

    if (/content-disposition:[^\r\n]*\bname="file"/i.test(headers)) {
      const filenameMatch = /\bfilename="([^"]*)"/i.exec(headers);
      const rawFilename = filenameMatch?.[1] ?? "lct-2026.geojson";
      const filename = rawFilename.split(/[\\/]/).pop()?.slice(0, 255) || "lct-2026.geojson";
      return { filename, bytes: body.subarray(dataStart, dataEnd) };
    }
    cursor = dataEnd + nextBoundary.length;
  }
  return undefined;
}

export function createLocalDemoServer({ bundle, contracts = new Map() }) {
  let currentImport = bundle.import;
  const expectedSize = Number(bundle.import.input_size_bytes);
  const expectedSha256 = String(bundle.run.input_sha256);
  const maximumMultipartBytes = expectedSize + 1024 * 1024;

  return createServer(async (request, response) => {
    const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
    const path = url.pathname;

    if (request.method === "POST" && path === "/api/v1/official/imports") {
      try {
        const declaredLength = Number(request.headers["content-length"] ?? 0);
        if (declaredLength > maximumMultipartBytes) {
          request.resume();
          send(response, 413, { message: "Размер GeoJSON превышает предел локального демонстрационного стенда" });
          return;
        }
        const body = await requestBody(request, maximumMultipartBytes);
        const file = extractMultipartFile(body, request.headers["content-type"]);
        if (!file) {
          send(response, 400, { message: "В multipart-запросе отсутствует файл GeoJSON" });
          return;
        }
        const sha256 = createHash("sha256").update(file.bytes).digest("hex");
        if (file.bytes.length !== expectedSize || sha256 !== expectedSha256) {
          send(response, 422, {
            message: "Локальный демонстрационный стенд не содержит результата для этого GeoJSON",
          });
          return;
        }
        currentImport = { ...bundle.import, original_filename: file.filename };
        send(response, 200, currentImport);
      } catch (error) {
        if (error?.code === "PAYLOAD_TOO_LARGE") {
          send(response, 413, { message: "Размер GeoJSON превышает предел локального демонстрационного стенда" });
          return;
        }
        send(response, 500, { message: "Не удалось прочитать загруженный файл" });
      }
      return;
    }

    if (request.method === "POST" && path === `/api/v1/official/imports/${bundle.import.id}/runs`) {
      request.resume();
      send(response, 200, bundle.run);
      return;
    }

    if (request.method !== "GET") {
      request.resume();
      send(response, 405, { message: "Операция недоступна в локальном демо" });
      return;
    }
    if (path === "/api/v1/health/ready") {
      send(response, 200, { status: "ready", checks: { local_demo: { status: "ok" } } });
      return;
    }
    if (contracts.has(path)) {
      sendSchema(response, contracts.get(path));
      return;
    }
    if (path === "/api/v1/official/runs/latest" || path === `/api/v1/official/runs/${bundle.run.id}`) {
      send(response, 200, bundle.run);
      return;
    }
    if (path === `/api/v1/official/imports/${bundle.import.id}`) {
      send(response, 200, currentImport);
      return;
    }
    if (path === `/api/v1/official/imports/${bundle.import.id}/map`) {
      send(response, 200, bundle.map);
      return;
    }
    send(response, 404, { message: "Ресурс локального стенда не найден" });
  });
}

async function start() {
  const bundlePath = resolve(process.argv[2] ?? "tmp/local-demo-bundle.json");
  const port = Number(process.env.HEATROUTE_DEMO_PORT ?? 8000);
  const bundle = JSON.parse(await readFile(bundlePath, "utf8"));
  const contracts = new Map(await Promise.all([...contractFiles].map(async ([name, filename]) => [
    `/api/v1/official/contracts/${name}.schema.json`,
    await readFile(new URL(`../docs/contracts/${filename}`, import.meta.url), "utf8"),
  ])));
  createLocalDemoServer({ bundle, contracts }).listen(port, "127.0.0.1", () => {
    process.stdout.write(`HeatRoute local demo API: http://127.0.0.1:${port}\n`);
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  await start();
}
