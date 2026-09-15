import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, ArrowRight, CheckCircle2, DatabaseZap, ShieldCheck } from "lucide-react";
import { Link, useParams } from "react-router-dom";

import { Badge, Card, StateView, StatusBadge } from "../components/ui/primitives";
import { getProjectQuality, listDatasetVersions } from "../shared/api";
import { displayValue, errorText, formatDate } from "../shared/format";

export function QualityPage() {
  const { projectId = "" } = useParams();
  const quality = useQuery({ queryKey: ["quality", projectId], queryFn: ({ signal }) => getProjectQuality(projectId, signal) });
  const versions = useQuery({ queryKey: ["dataset-versions", projectId], queryFn: ({ signal }) => listDatasetVersions(projectId, signal) });

  if (quality.isPending) return <StateView state="loading" title="Собираем отчёт качества" />;
  if (quality.isError) return <StateView state="error" title="Качество недоступно" detail={errorText(quality.error)} onRetry={() => void quality.refetch()} />;
  return (
    <div className="page quality-page">
      <header className="page-heading"><div><span className="eyebrow">Data readiness</span><h1>Качество данных</h1><p>Покрытие и пропуски не интерпретируются как вероятность безопасности.</p></div><Link className="button-link button-link--outline" to={`/projects/${projectId}/data`}><DatabaseZap size={15} /> Поставки <ArrowRight size={14} /></Link></header>
      <div className="quality-hero">
        <Card className="quality-score"><span className="quality-score__icon"><ShieldCheck size={22} /></span><div><small>Состояние покрытия</small><h2>{coverageLabel(quality.data.coverage_state)}</h2><p>{coverageDescription(quality.data.coverage_state)}</p></div><StatusBadge value={quality.data.coverage_state} /></Card>
        <Card><small>Опубликованные версии</small><strong className="big-number">{versions.data?.filter((item) => item.status === "published").length ?? 0}</strong><p className="muted-copy">Каждая version immutable</p></Card>
        <Card><small>Открытые findings</small><strong className="big-number">{quality.data.findings.length}</strong><p className="muted-copy">Не скрываются в экспорте</p></Card>
      </div>
      <div className="quality-grid">
        <Card className="table-card">
          <header><div><h2>Findings</h2><p>Проблемы покрытия, полей и топологии из опубликованных отчётов</p></div></header>
          {quality.data.findings.length === 0 ? <StateView compact state="empty" title="Findings не зарегистрированы" detail="Это означает отсутствие зарегистрированных проблем, а не доказанную полноту данных." /> : (
            <div className="finding-list">{quality.data.findings.map((finding, index) => {
              const code = displayValue(finding.code, `FINDING-${index + 1}`);
              const severity = displayValue(finding.severity, "warning");
              return <article key={`${code}-${index}`}><span className={severity === "error" ? "finding-icon is-error" : "finding-icon"}><AlertTriangle size={16} /></span><div><span><strong>{code}</strong><Badge tone={severity === "error" ? "danger" : "warning"}>{severity}</Badge></span><p>{displayValue(finding.message ?? finding.description, "Требуется ручная проверка")}</p><small>{finding.field ? `Поле: ${displayValue(finding.field)}` : "Источник указан в import report"}</small></div></article>;
            })}</div>
          )}
        </Card>
        <Card className="version-quality">
          <h2>Версии данных</h2>
          {(versions.data ?? []).map((version) => <article key={version.id}><span className={version.status === "published" ? "version-check is-ok" : "version-check"}>{version.status === "published" ? <CheckCircle2 size={15} /> : <AlertTriangle size={15} />}</span><div><strong>{version.source_name} · v{version.version}</strong><small>{version.working_crs ?? "CRS не подтверждена"} · {formatDate(version.created_at)}</small></div><StatusBadge value={version.status} /></article>)}
          {versions.data?.length === 0 && <p className="muted-copy">Версий пока нет.</p>}
        </Card>
      </div>
    </div>
  );
}

function coverageLabel(value: string) {
  return { known_complete: "Известно полное", partial: "Частичное", unknown: "Неизвестное", synthetic: "Синтетическое" }[value] ?? value;
}
function coverageDescription(value: string) {
  if (value === "known_complete") return "Границы применимости подтверждены поставщиком данных.";
  if (value === "synthetic") return "Данные созданы для демонстрации и не описывают реальный объект.";
  if (value === "partial") return "Часть территории или атрибутов не покрыта.";
  return "Границы применимости не подтверждены — выводы должны сохранять статус unknown.";
}
