import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const bundlePath = resolve(process.argv[2] ?? "tmp/local-demo-bundle.json");
const port = Number(process.env.HEATROUTE_DEMO_PORT ?? 8000);
const bundle = JSON.parse(await readFile(bundlePath, "utf8"));

function send(response, status, payload) {
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
  });
  response.end(JSON.stringify(payload));
}

createServer((request, response) => {
  const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
  const path = url.pathname;

  if (request.method !== "GET") {
    send(response, 405, { message: "Локальный стенд доступен только для просмотра" });
    return;
  }
  if (path === "/api/v1/health/ready") {
    send(response, 200, { status: "ready", checks: { local_demo: { status: "ok" } } });
    return;
  }
  if (path === "/api/v1/official/runs/latest" || path === `/api/v1/official/runs/${bundle.run.id}`) {
    send(response, 200, bundle.run);
    return;
  }
  if (path === `/api/v1/official/imports/${bundle.import.id}`) {
    send(response, 200, bundle.import);
    return;
  }
  if (path === `/api/v1/official/imports/${bundle.import.id}/map`) {
    send(response, 200, bundle.map);
    return;
  }
  send(response, 404, { message: "Ресурс локального стенда не найден" });
}).listen(port, "127.0.0.1", () => {
  process.stdout.write(`HeatRoute local demo API: http://127.0.0.1:${port}\n`);
});
