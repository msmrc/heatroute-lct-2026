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

const configuredApiBase: unknown = import.meta.env.VITE_API_BASE_URL;
const rawBase = typeof configuredApiBase === "string" ? configuredApiBase : "/api/v1";
const absoluteBase = rawBase.startsWith("/") ? `${window.location.origin}${rawBase}` : rawBase;
export const API_BASE = absoluteBase.replace(/\/$/, "");

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

export function createTopologyJob(importId: string): Promise<OfficialJob> {
  return request(`/official/imports/${encodeURIComponent(importId)}/jobs/topology`, { method: "POST" });
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
