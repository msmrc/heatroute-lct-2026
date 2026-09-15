import createClient from "openapi-fetch";

import type {
  paths,
  SchemaCapabilitiesResponse,
  SchemaCanonicalFeatureResponse,
  SchemaCostCatalogCreate,
  SchemaCostCatalogResponse,
  SchemaDatasetImportResponse,
  SchemaDatasetLayerResponse,
  SchemaDatasetPublishRequest,
  SchemaDatasetUploadAcceptedResponse,
  SchemaDatasetVersionDiffResponse,
  SchemaDatasetVersionResponse,
  SchemaImportReportResponse,
  SchemaJobResponse,
  SchemaMappingPutRequest,
  SchemaPreflightResponse,
  SchemaProjectCreate,
  SchemaProjectQualityResponse,
  SchemaProjectResponse,
  SchemaProjectUpdate,
  SchemaRuleProfileCreate,
  SchemaRuleProfileResponse,
  SchemaRunAcceptedResponse,
  SchemaRunCancelResponse,
  SchemaRunEventResponse,
  SchemaRunResponse,
  SchemaRunSummaryResponse,
  SchemaScenarioResponse,
  SchemaScenarioRevisionCreate,
  SchemaScenarioRevisionResponse,
} from "./api-schema";

export type Capabilities = SchemaCapabilitiesResponse;
export type Project = SchemaProjectResponse;
export type ProjectCreate = SchemaProjectCreate;
export type ProjectUpdate = SchemaProjectUpdate;
export type Scenario = SchemaScenarioResponse;
export type ScenarioRevision = SchemaScenarioRevisionResponse;
export type ScenarioDraft = SchemaScenarioRevisionCreate;
export type Preflight = SchemaPreflightResponse;
export type CalculationRun = SchemaRunResponse;
export type RunSummary = SchemaRunSummaryResponse;
export type RouteAlternative = CalculationRun["alternatives"][number];
export type RunEvent = SchemaRunEventResponse;
export type Job = SchemaJobResponse;
export type DatasetImport = SchemaDatasetImportResponse;
export type DatasetVersion = SchemaDatasetVersionResponse;
export type DatasetLayer = SchemaDatasetLayerResponse;
export type DatasetDiff = SchemaDatasetVersionDiffResponse;
export type ImportReport = SchemaImportReportResponse;
export type MappingDraft = SchemaMappingPutRequest;
export type QualityReport = SchemaProjectQualityResponse;
export type CanonicalFeature = SchemaCanonicalFeatureResponse;
export type RuleProfile = SchemaRuleProfileResponse;
export type CostCatalog = SchemaCostCatalogResponse;

const configuredApiBase: unknown = import.meta.env.VITE_API_BASE_URL;
const rawBase = typeof configuredApiBase === "string" ? configuredApiBase : "/api/v1";
const absoluteBase = rawBase.startsWith("/") ? `${window.location.origin}${rawBase}` : rawBase;
export const API_ORIGIN = absoluteBase.replace(/\/api\/v1\/?$/, "");
export const API_BASE = `${API_ORIGIN}/api/v1`;

const client = createClient<paths>({
  baseUrl: API_ORIGIN,
  credentials: "include",
  fetch: (input: Request) => globalThis.fetch(input),
});

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status?: number,
    readonly detail?: unknown,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

function errorMessage(error: unknown, response?: Response): string {
  if (error && typeof error === "object" && "detail" in error) {
    const detail = (error as { detail?: unknown }).detail;
    if (typeof detail === "string") return detail;
    if (detail && typeof detail === "object" && "message" in detail) {
      const message = (detail as { message?: unknown }).message;
      if (typeof message === "string") return message;
    }
  }
  return response ? `API вернул ${response.status}` : "Не удалось связаться с API";
}

function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.data !== undefined) return result.data;
  throw new ApiError(errorMessage(result.error, result.response), result.response.status, result.error);
}

export async function fetchCapabilities(signal?: AbortSignal): Promise<Capabilities> {
  return unwrap(await client.GET("/api/v1/capabilities", { signal }));
}

export function capabilitySummary(capabilities: Capabilities): string {
  if (capabilities.solvers.length === 0) return "Расчётное ядро ещё не подключено";
  return `API на связи · ${capabilities.solvers.length} алгоритма`;
}

export async function listProjects(signal?: AbortSignal): Promise<readonly Project[]> {
  return unwrap(await client.GET("/api/v1/projects", { signal }));
}

export async function getProject(projectId: string, signal?: AbortSignal): Promise<Project> {
  return unwrap(await client.GET("/api/v1/projects/{project_id}", {
    params: { path: { project_id: projectId } }, signal,
  }));
}

export async function createProject(body: ProjectCreate): Promise<Project> {
  return unwrap(await client.POST("/api/v1/projects", { body }));
}

export async function updateProject(project: Project, body: ProjectUpdate): Promise<Project> {
  return unwrap(await client.PATCH("/api/v1/projects/{project_id}", {
    params: { path: { project_id: project.id }, header: { "If-Match": String(project.current_revision) } },
    body,
  }));
}

export async function listScenarios(projectId: string, signal?: AbortSignal): Promise<readonly Scenario[]> {
  return unwrap(await client.GET("/api/v1/projects/{project_id}/scenarios", {
    params: { path: { project_id: projectId } }, signal,
  }));
}

export async function getScenario(scenarioId: string, signal?: AbortSignal): Promise<Scenario> {
  return unwrap(await client.GET("/api/v1/scenarios/{scenario_id}", {
    params: { path: { scenario_id: scenarioId } }, signal,
  }));
}

export async function createScenario(projectId: string, name: string): Promise<Scenario> {
  return unwrap(await client.POST("/api/v1/projects/{project_id}/scenarios", {
    params: { path: { project_id: projectId } }, body: { name },
  }));
}

export async function createScenarioRevision(
  scenarioId: string,
  expectedRevision: number,
  body: ScenarioDraft,
): Promise<ScenarioRevision> {
  return unwrap(await client.POST("/api/v1/scenarios/{scenario_id}/revisions", {
    params: {
      path: { scenario_id: scenarioId },
      header: { "If-Match": String(expectedRevision) },
    },
    body,
  }));
}

export async function preflightRevision(revisionId: string): Promise<Preflight> {
  return unwrap(await client.POST("/api/v1/scenario-revisions/{revision_id}/preflight", {
    params: { path: { revision_id: revisionId } },
  }));
}

export async function startRevisionRun(
  revisionId: string,
  algorithm: "astar" | "dijkstra",
): Promise<SchemaRunAcceptedResponse> {
  return unwrap(await client.POST("/api/v1/scenario-revisions/{revision_id}/runs", {
    params: {
      path: { revision_id: revisionId },
      header: { "Idempotency-Key": crypto.randomUUID() },
    },
    body: { algorithm },
  }));
}

export async function startDemoRun(includeObstacle: boolean): Promise<SchemaRunAcceptedResponse> {
  return unwrap(await client.POST("/api/v1/demo/runs", {
    params: { header: { "Idempotency-Key": crypto.randomUUID() } },
    body: {
      input_mode: "point_to_point_demo",
      entry_point_wgs84: [37.61, 55.752],
      goal_point_wgs84: [37.64, 55.752],
      forbidden_rectangles_wgs84: includeObstacle ? [[37.623, 55.748, 37.628, 55.756]] : [],
      corridor_width_m: 8,
      algorithm: "astar",
      search_settings: {
        resolution_m: 20,
        search_buffer_m: 500,
        budget: { max_expanded_states: 100_000, max_wall_time_s: 120, max_memory_mb: 4096 },
        max_alternatives: 3,
      },
      explicit_assumptions: ["Синтетический 2D-сценарий; не инженерное заключение"],
    },
  }));
}

export async function fetchRun(runId: string, signal?: AbortSignal): Promise<CalculationRun> {
  return unwrap(await client.GET("/api/v1/runs/{run_id}", {
    params: { path: { run_id: runId } }, signal,
  }));
}

export async function listRuns(projectId: string, signal?: AbortSignal): Promise<readonly RunSummary[]> {
  return unwrap(await client.GET("/api/v1/projects/{project_id}/runs", {
    params: { path: { project_id: projectId }, query: { limit: 100 } }, signal,
  }));
}

export async function fetchRunEvents(runId: string, afterSequence = 0, signal?: AbortSignal): Promise<readonly RunEvent[]> {
  return unwrap(await client.GET("/api/v1/runs/{run_id}/events", {
    params: { path: { run_id: runId }, query: { after_sequence: afterSequence } }, signal,
  }));
}

export async function cancelRun(runId: string): Promise<SchemaRunCancelResponse> {
  return unwrap(await client.POST("/api/v1/runs/{run_id}/cancel", {
    params: { path: { run_id: runId } },
  }));
}

export function runEventStreamUrl(runId: string, afterSequence = 0): string {
  return `${API_BASE}/runs/${encodeURIComponent(runId)}/events/stream?after_sequence=${afterSequence}`;
}

export function runExportUrl(runId: string, format: "geojson" | "json" | "csv" | "html"): string {
  return `${API_BASE}/runs/${encodeURIComponent(runId)}/export?format=${format}`;
}

export async function listJobs(projectId?: string, signal?: AbortSignal): Promise<readonly Job[]> {
  return unwrap(await client.GET("/api/v1/jobs", {
    params: { query: { limit: 100, project_id: projectId } }, signal,
  }));
}

export async function uploadDataset(
  projectId: string,
  file: File,
  fields: { datasetName: string; purpose: string; licenseNote: string },
): Promise<SchemaDatasetUploadAcceptedResponse> {
  const form = new FormData();
  form.set("file", file);
  form.set("dataset_name", fields.datasetName);
  form.set("purpose", fields.purpose);
  if (fields.licenseNote) form.set("license_note", fields.licenseNote);
  const response = await fetch(`${API_BASE}/projects/${encodeURIComponent(projectId)}/datasets/uploads`, {
    method: "POST", body: form, credentials: "include",
  });
  const payload = await response.json() as SchemaDatasetUploadAcceptedResponse | { detail?: unknown };
  if (!response.ok) throw new ApiError(errorMessage(payload, response), response.status, payload);
  return payload as SchemaDatasetUploadAcceptedResponse;
}

export async function listImports(projectId: string, signal?: AbortSignal): Promise<readonly DatasetImport[]> {
  return unwrap(await client.GET("/api/v1/projects/{project_id}/imports", {
    params: { path: { project_id: projectId } }, signal,
  }));
}

export async function getImport(importId: string, signal?: AbortSignal): Promise<DatasetImport> {
  return unwrap(await client.GET("/api/v1/imports/{import_id}", {
    params: { path: { import_id: importId } }, signal,
  }));
}

export async function getImportReport(importId: string, signal?: AbortSignal): Promise<ImportReport> {
  return unwrap(await client.GET("/api/v1/imports/{import_id}/report", {
    params: { path: { import_id: importId } }, signal,
  }));
}

export async function saveImportMapping(importId: string, body: MappingDraft) {
  return unwrap(await client.PUT("/api/v1/imports/{import_id}/mapping", {
    params: { path: { import_id: importId } }, body,
  }));
}

export async function validateImport(importId: string): Promise<DatasetImport> {
  return unwrap(await client.POST("/api/v1/imports/{import_id}/validate", {
    params: { path: { import_id: importId } },
  }));
}

export async function publishImport(importId: string, body: SchemaDatasetPublishRequest): Promise<DatasetImport> {
  return unwrap(await client.POST("/api/v1/imports/{import_id}/publish", {
    params: { path: { import_id: importId } }, body,
  }));
}

export async function listDatasetVersions(projectId: string, signal?: AbortSignal): Promise<readonly DatasetVersion[]> {
  return unwrap(await client.GET("/api/v1/projects/{project_id}/dataset-versions", {
    params: { path: { project_id: projectId } }, signal,
  }));
}

export async function listDatasetLayers(projectId: string, versionId: string, signal?: AbortSignal): Promise<readonly DatasetLayer[]> {
  return unwrap(await client.GET("/api/v1/projects/{project_id}/dataset-versions/{version_id}/layers", {
    params: { path: { project_id: projectId, version_id: versionId } }, signal,
  }));
}

export async function getDatasetDiff(projectId: string, versionId: string, signal?: AbortSignal): Promise<DatasetDiff> {
  return unwrap(await client.GET("/api/v1/projects/{project_id}/dataset-versions/{version_id}/diff", {
    params: { path: { project_id: projectId, version_id: versionId } }, signal,
  }));
}

export async function getProjectQuality(projectId: string, signal?: AbortSignal): Promise<QualityReport> {
  return unwrap(await client.GET("/api/v1/projects/{project_id}/quality", {
    params: { path: { project_id: projectId } }, signal,
  }));
}

export async function listFeatures(projectId: string, versionId: string, signal?: AbortSignal): Promise<readonly CanonicalFeature[]> {
  return unwrap(await client.GET("/api/v1/projects/{project_id}/dataset-versions/{version_id}/features", {
    params: { path: { project_id: projectId, version_id: versionId }, query: { limit: 100, offset: 0 } }, signal,
  }));
}

export async function listRuleProfiles(projectId: string, signal?: AbortSignal): Promise<readonly RuleProfile[]> {
  return unwrap(await client.GET("/api/v1/projects/{project_id}/rule-profiles", {
    params: { path: { project_id: projectId } }, signal,
  }));
}

export async function createRuleProfile(projectId: string, body: SchemaRuleProfileCreate): Promise<RuleProfile> {
  return unwrap(await client.POST("/api/v1/projects/{project_id}/rule-profiles", {
    params: { path: { project_id: projectId } }, body,
  }));
}

export async function reviseRuleProfile(profile: RuleProfile, definition: Record<string, unknown>): Promise<RuleProfile> {
  return unwrap(await client.POST("/api/v1/rule-profiles/{profile_id}/versions", {
    params: { path: { profile_id: profile.id }, header: { "If-Match": String(profile.current_revision) } },
    body: { definition },
  }));
}

export async function listCostCatalogs(projectId: string, signal?: AbortSignal): Promise<readonly CostCatalog[]> {
  return unwrap(await client.GET("/api/v1/projects/{project_id}/cost-catalogs", {
    params: { path: { project_id: projectId } }, signal,
  }));
}

export async function createCostCatalog(projectId: string, body: SchemaCostCatalogCreate): Promise<CostCatalog> {
  return unwrap(await client.POST("/api/v1/projects/{project_id}/cost-catalogs", {
    params: { path: { project_id: projectId } }, body,
  }));
}

export async function reviseCostCatalog(catalog: CostCatalog, definition: Record<string, unknown>): Promise<CostCatalog> {
  return unwrap(await client.POST("/api/v1/cost-catalogs/{catalog_id}/versions", {
    params: { path: { catalog_id: catalog.id }, header: { "If-Match": String(catalog.current_revision) } },
    body: { definition },
  }));
}

export function costCatalogExportUrl(catalogId: string, format: "json" | "csv" | "html"): string {
  return `${API_BASE}/cost-catalogs/${encodeURIComponent(catalogId)}/export?format=${format}`;
}
