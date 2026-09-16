export interface DependencyStatus {
  status: "ok" | "error";
  detail?: string;
}

export interface Readiness {
  status: "ready" | "not_ready";
  checks: Record<string, DependencyStatus>;
}

export interface OfficialInputError {
  code: string;
  feature_index: number;
  feature_id?: string;
  field?: string;
  message: string;
}

export type OfficialInputWarning = OfficialInputError;

export interface OfficialInputReport {
  contract_version: string;
  input_profile: "strict_official" | "provided_dataset_compatibility";
  sha256: string;
  feature_count: number;
  feature_counts: Record<string, number>;
  errors: OfficialInputError[];
  warnings: OfficialInputWarning[];
  valid: boolean;
}

export interface OfficialImport {
  id: string;
  state: string;
  original_filename: string;
  input_size_bytes: number;
  created_at: string;
  report: OfficialInputReport;
}

export interface OfficialJob {
  id: string;
  import_id: string;
  run_id?: string;
  job_type: string;
  state: string;
  phase: string;
  progress_current: number;
  progress_total: number;
  attempt: number;
  cancellation_requested: boolean;
  result?: unknown;
  error_code?: string;
  error_message?: string;
  created_at: string;
  completed_at?: string;
}

export interface OfficialRun {
  id: string;
  import_id: string;
  job_id?: string;
  state: string;
  algorithm_version: string;
  input_sha256: string;
  result?: OfficialCalculationResult;
  error_code?: string;
  error_message?: string;
  created_at: string;
  completed_at?: string;
}

export interface OfficialRouteCoordinate {
  xm: number;
  ym: number;
}

export interface OfficialRouteNode {
  id: string;
  root: boolean;
  chamber: boolean;
  node_type: string;
  target_id?: string | null;
  coordinate: OfficialRouteCoordinate;
  base_incident_sections: number;
}

export interface OfficialRouteEdge {
  id: string;
  length_m: number;
  upstream_node_id: string;
  downstream_node_id: string;
  coordinates?: OfficialRouteCoordinate[];
  sections?: OfficialRouteSection[];
  flow_tph?: number | null;
  diameter?: number | null;
}

export interface OfficialRouteSection {
  kind: "base" | "special";
  length_m: number;
  coordinates: OfficialRouteCoordinate[];
  restriction_type?: string | null;
  restriction_id?: string | null;
  crossing_angle_degrees?: number | null;
}

export interface OfficialRouteConnection {
  status: "connected" | "no_route";
  flow_tph: number;
  demand_id: string;
  connection_point_id: string;
  reason?: string;
}

export interface OfficialCalculationIssue {
  code: string;
  message: string;
  subject_id?: string | null;
  edge_id?: string | null;
}

export interface OfficialNetworkReconstructionSection {
  id: string;
  existing_feature_id: string;
  coordinates: OfficialRouteCoordinate[];
  length_m: number;
  existing_flow_tph: number;
  added_flow_tph: number;
  resulting_flow_tph: number;
  existing_diameter: number;
  required_diameter: number;
  partial: boolean;
}

export interface OfficialChamberReconstruction {
  existing_feature_id: string;
  coordinate: OfficialRouteCoordinate;
  added_flow_tph: number;
  resulting_flow_tph: number;
  existing_diameter: number;
  required_diameter: number;
}

export interface OfficialReconstructionResult {
  available: boolean;
  network_sections: OfficialNetworkReconstructionSection[];
  chambers: OfficialChamberReconstruction[];
  issues: OfficialCalculationIssue[];
}

export interface OfficialVariantEconomics {
  complete: boolean;
  construction_cost: number;
  chamber_construction_cost: number;
  tie_in_cost: number;
  reconstruction_cost: number;
  chamber_reconstruction_cost: number;
  unconnected_penalty: number;
  calculated_cost: number;
  new_network_length: number;
  reconstruction_length: number;
  length: number;
  score?: number | null;
  incomplete_reasons: string[];
}

export interface OfficialRouteVariant {
  id: string;
  strategy: string;
  valid: boolean;
  nodes: OfficialRouteNode[];
  edges: OfficialRouteEdge[];
  connections: OfficialRouteConnection[];
  total_length_m: number;
  validation_issues: OfficialCalculationIssue[];
  sizing_issues?: OfficialCalculationIssue[];
  reconstruction?: OfficialReconstructionResult;
  economics?: OfficialVariantEconomics;
  rank?: number | null;
  no_route_demand_count: number;
  connected_demand_count: number;
}

export interface OfficialCalculationResult {
  variants: OfficialRouteVariant[];
  demand_count: number;
  algorithm_version: string;
  preferred_variant_id: string;
}

export interface OfficialMapBounds {
  minLon: number;
  minLat: number;
  maxLon: number;
  maxLat: number;
}

export interface OfficialMapFeatureCollection {
  type: "FeatureCollection";
  features: unknown[];
  truncated: boolean;
}

export interface OfficialOutputFeatureCollection {
  type: "FeatureCollection";
  features: unknown[];
}

const configuredApiBase: unknown = import.meta.env.VITE_API_BASE_URL;
const rawBase = typeof configuredApiBase === "string" ? configuredApiBase : "/api/v1";
const absoluteBase = rawBase.startsWith("/") ? `${window.location.origin}${rawBase}` : rawBase;
export const API_BASE = absoluteBase.replace(/\/$/, "");

export function officialExportUrl(runId: string): string {
  return `${API_BASE}/official/runs/${runId}/export`;
}

export function getOfficialVariantOutput(
  runId: string,
  variantId: string,
  signal?: AbortSignal,
): Promise<OfficialOutputFeatureCollection> {
  const query = new URLSearchParams({ variant_id: variantId });
  return request(`/official/runs/${encodeURIComponent(runId)}/export?${query}`, { signal });
}

export class ApiError extends Error {
  constructor(message: string, readonly status?: number, readonly detail?: unknown) {
    super(message);
    this.name = "ApiError";
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, init);
  const payload = await response.json().catch(() => undefined) as { message?: string; detail?: unknown } | undefined;
  if (!response.ok) {
    throw new ApiError(payload?.message ?? `API вернул ${response.status}`, response.status, payload);
  }
  return payload as T;
}

export function fetchReadiness(signal?: AbortSignal): Promise<Readiness> {
  return request("/health/ready", { signal });
}

export function createOfficialImport(file: File): Promise<OfficialImport> {
  const body = new FormData();
  body.set("file", file);
  return request("/official/imports", { method: "POST", body });
}

export function getOfficialImport(importId: string, signal?: AbortSignal): Promise<OfficialImport> {
  return request(`/official/imports/${encodeURIComponent(importId)}`, { signal });
}

export function getOfficialMap(
  importId: string,
  bounds: OfficialMapBounds,
  signal?: AbortSignal,
): Promise<OfficialMapFeatureCollection> {
  const query = new URLSearchParams(Object.entries(bounds).map(([key, value]) => [key, String(value)]));
  return request(`/official/imports/${encodeURIComponent(importId)}/map?${query}`, { signal });
}

export function createTopologyJob(importId: string): Promise<OfficialJob> {
  return request(`/official/imports/${encodeURIComponent(importId)}/jobs/topology`, { method: "POST" });
}

export function createOfficialRun(importId: string): Promise<OfficialRun> {
  return request(`/official/imports/${encodeURIComponent(importId)}/runs`, { method: "POST" });
}

export function getOfficialRun(runId: string, signal?: AbortSignal): Promise<OfficialRun> {
  return request(`/official/runs/${encodeURIComponent(runId)}`, { signal });
}

export function getLatestOfficialRun(signal?: AbortSignal): Promise<OfficialRun> {
  return request("/official/runs/latest", { signal });
}

export function getOfficialJob(jobId: string, signal?: AbortSignal): Promise<OfficialJob> {
  return request(`/official/jobs/${encodeURIComponent(jobId)}`, { signal });
}

export function cancelOfficialJob(jobId: string): Promise<OfficialJob> {
  return request(`/official/jobs/${encodeURIComponent(jobId)}`, { method: "DELETE" });
}

export function humanFileSize(value: number): string {
  if (value < 1024) return `${value} Б`;
  if (value < 1024 ** 2) return `${(value / 1024).toFixed(1)} КБ`;
  return `${(value / 1024 ** 2).toFixed(1)} МБ`;
}
