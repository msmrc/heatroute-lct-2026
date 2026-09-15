import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, Copy, Download, Route, ShieldCheck } from "lucide-react";
import { Link, useParams } from "react-router-dom";
import { toast } from "sonner";

import { Badge, Card, StateView, StatusBadge } from "../components/ui/primitives";
import { WorkspaceMap } from "../features/map/WorkspaceMap";
import { fetchRun, fetchRunEvents, runExportUrl } from "../shared/api";
import { displayValue, errorText, formatDate, formatMoney, formatNumber } from "../shared/format";
import { draftFromSnapshot } from "../shared/scenario";

const allLayers = { constraints: true, datasets: true, routes: true, corridors: true, endpoints: true, findings: true };

export function RunPage() {
  const { projectId = "", runId = "" } = useParams();
  const run = useQuery({ queryKey: ["run", runId], queryFn: ({ signal }) => fetchRun(runId, signal) });
  const events = useQuery({ queryKey: ["run-events", runId], queryFn: ({ signal }) => fetchRunEvents(runId, 0, signal) });
  if (run.isPending) return <StateView state="loading" title="Открываем паспорт run" />;
  if (run.isError) return <StateView state="error" title="Run недоступен" detail={errorText(run.error)} onRetry={() => void run.refetch()} />;
  const draft = draftFromSnapshot(run.data.parameters);
  const copy = (value: string) => void navigator.clipboard.writeText(value).then(() => toast.success("Скопировано"));
  return (
    <div className="page run-page">
      <header className="page-heading page-heading--compact">
        <div><Link className="back-link" to={`/projects/${projectId}/workspace?run=${runId}`}><ArrowLeft size={15} /> В workspace</Link><h1>Паспорт расчёта</h1><p>Immutable server result · завершён {formatDate(run.data.finished_at)}</p></div>
        <StatusBadge value={run.data.job_state} />
      </header>
      <div className="run-hero">
        <Card><span>Outcome</span><strong>{run.data.outcome}</strong><StatusBadge value={run.data.search_completion} /></Card>
        <Card><span>Алгоритм</span><strong>{run.data.algorithm_name.toUpperCase()}</strong><small>{run.data.algorithm_version}</small></Card>
        <Card><span>Варианты</span><strong>{run.data.alternatives.length}</strong><small>{run.data.optimality_scope.replaceAll("_", " ")}</small></Card>
        <Card><span>События</span><strong>{events.data?.length ?? "—"}</strong><small>с сохранённой sequence</small></Card>
      </div>
      <div className="run-layout">
        <Card className="run-map-card"><div className="run-map"><WorkspaceMap alternatives={run.data.alternatives} selectedRank={1} draft={draft} layers={allLayers} /></div></Card>
        <Card className="passport-card">
          <h2>Воспроизводимость</h2>
          <dl>{[
            { label: "Run ID", value: run.data.id },
            { label: "Job ID", value: run.data.job_id },
            { label: "Scenario revision", value: run.data.versions_snapshot.scenario_revision_id },
            { label: "Input hash", value: run.data.versions_snapshot.input_hash },
          ].map(({ label, value }) => <div key={label}><dt>{label}</dt><dd><code>{displayValue(value)}</code><button type="button" className="icon-button" onClick={() => copy(displayValue(value, ""))}><Copy size={13} /></button></dd></div>)}</dl>
          <h3>Runtime</h3><div className="field-chips">{Object.entries(run.data.runtime_library_versions).map(([key, value]) => <Badge key={key}>{key} {displayValue(value)}</Badge>)}</div>
          <h3>Выгрузки</h3><div className="export-grid">{(["geojson", "html", "json", "csv"] as const).map((format) => <a key={format} className="export-link" href={runExportUrl(runId, format)} target={format === "html" ? "_blank" : undefined} rel="noreferrer"><Download size={14} /> {format.toUpperCase()}</a>)}</div>
        </Card>
      </div>
      <Card className="table-card">
        <header><div><h2>Сравнение вариантов</h2><p>Длина, стоимость, полнота и независимый статус геометрии</p></div></header>
        <div className="simple-table alternatives-table">
          <div className="simple-table__head"><span>Вариант</span><span>Цели</span><span>Длина</span><span>Стоимость</span><span>Полнота цены</span><span>Проверка</span></div>
          {run.data.alternatives.map((item) => <div className="simple-table__row" key={item.id}><span><span className="alternative-letter">{String.fromCharCode(64 + item.rank)}</span><strong>{item.candidate_id ?? "point-to-point"}</strong></span><span>{item.objective_tags.join(", ")}</span><span>{formatNumber(item.metrics.route_length_m, 2)} м</span><span>{formatMoney(item.cost_breakdown.total)}</span><StatusBadge value={displayValue(item.cost_breakdown.status, "unknown")} /><StatusBadge value={item.geometry_status} /></div>)}
        </div>
      </Card>
      <div className="run-detail-grid">
        <Card><h2><ShieldCheck size={17} /> Findings</h2><pre>{JSON.stringify(run.data.findings_summary, null, 2)}</pre></Card>
        <Card><h2><Route size={17} /> Quantities</h2><pre>{JSON.stringify(run.data.alternatives[0]?.quantity_items ?? [], null, 2)}</pre></Card>
        <Card><h2>Assumptions</h2><pre>{JSON.stringify(run.data.assumptions, null, 2)}</pre></Card>
      </div>
    </div>
  );
}
