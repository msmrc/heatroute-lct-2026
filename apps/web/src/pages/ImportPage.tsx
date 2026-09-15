import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ArrowLeft,
  ArrowRight,
  Check,
  FileSearch,
  FileUp,
  Link2,
  Rocket,
  ShieldCheck,
} from "lucide-react";
import { useEffect, useMemo, useState, type FormEvent } from "react";
import { Link, useParams } from "react-router-dom";
import { toast } from "sonner";

import { Button } from "../components/ui/button";
import { Badge, Card, CheckLine, Field, ProgressBar, StateView, StatusBadge } from "../components/ui/primitives";
import {
  getImport,
  getImportReport,
  publishImport,
  saveImportMapping,
  validateImport,
  type MappingDraft,
} from "../shared/api";
import { displayValue, errorText, formText, formatDate, formatNumber, shortId } from "../shared/format";

const steps = [
  { label: "Файл", icon: FileUp },
  { label: "Инспекция", icon: FileSearch },
  { label: "Сопоставление", icon: Link2 },
  { label: "Проверка", icon: ShieldCheck },
  { label: "Публикация", icon: Rocket },
];
const activeByState: Record<string, number> = {
  uploaded: 1,
  inspecting: 1,
  mapping_required: 2,
  ready_to_validate: 3,
  validating: 3,
  needs_review: 3,
  ready_to_publish: 4,
  publishing: 4,
  published: 5,
  rejected: 1,
  failed: 1,
};

type MappingKind = MappingDraft["target_kind"];
type RequiredMappingField = { key: string; label: string; constants?: string[] };
const requiredFieldsByKind: Partial<Record<MappingKind, RequiredMappingField[]>> = {
  building: [{ key: "building_role", label: "Роль здания", constants: ["existing", "target", "planned"] }],
  road: [{ key: "crossing_policy", label: "Политика пересечения", constants: ["unknown", "profile_defined", "portal_only", "forbidden"] }],
  utility_line: [{ key: "utility_type", label: "Тип коммуникации", constants: ["unknown", "heat", "water", "sewer", "electric", "gas", "telecom", "other"] }],
  network_node: [
    { key: "node_type", label: "Тип узла", constants: ["unknown"] },
    { key: "source_network_id", label: "ID исходной сети", constants: ["uploaded"] },
    { key: "circuit", label: "Контур", constants: ["unknown", "supply", "return", "paired_corridor"] },
    { key: "connection_permission", label: "Разрешение подключения", constants: ["unknown", "allowed", "forbidden"] },
  ],
  network_edge: [
    { key: "from_node_id", label: "Начальный узел" },
    { key: "to_node_id", label: "Конечный узел" },
    { key: "source_network_id", label: "ID исходной сети", constants: ["uploaded"] },
    { key: "circuit", label: "Контур", constants: ["unknown", "supply", "return", "paired_corridor"] },
    { key: "status", label: "Статус", constants: ["unknown"] },
  ],
  forbidden_zone: [
    { key: "restriction_kind", label: "Тип ограничения", constants: ["restricted"] },
    { key: "severity", label: "Строгость", constants: ["hard"] },
  ],
};

function defaultRequiredChoice(field: RequiredMappingField, sourceFields: string[], fallbackSource: string): string {
  const exact = sourceFields.find((name) => name.toLowerCase() === field.key.toLowerCase());
  if (exact) return `source:${exact}`;
  if (field.constants?.[0]) return `constant:${field.constants[0]}`;
  return `source:${fallbackSource}`;
}

export function ImportPage() {
  const { projectId = "", importId = "" } = useParams();
  const queryClient = useQueryClient();
  const [confirmQuarantine, setConfirmQuarantine] = useState(false);
  const [coverageLimitations, setCoverageLimitations] = useState("");
  const [mappingKind, setMappingKind] = useState<MappingKind>("building");
  const datasetImport = useQuery({
    queryKey: ["import", projectId, importId],
    queryFn: ({ signal }) => getImport(importId, signal),
    refetchInterval: (query) => ["queued", "running"].includes(query.state.data?.job_state ?? "") ? 800 : false,
  });
  const report = useQuery({
    queryKey: ["import-report", projectId, importId, datasetImport.data?.state],
    queryFn: ({ signal }) => getImportReport(importId, signal),
    enabled: Boolean(datasetImport.data && !["uploaded", "inspecting"].includes(datasetImport.data.state)),
    retry: 2,
  });
  const activeStep = activeByState[datasetImport.data?.state ?? "uploaded"] ?? 1;
  const firstLayer = report.data?.layers[0];
  const suggestedId = useMemo(() => {
    const names = firstLayer?.fields.map((item) => item.name) ?? [];
    return names.find((name) => /(^id$|_id$|code|bid)/i.test(name)) ?? names[0] ?? "";
  }, [firstLayer]);
  const suggestedName = useMemo(() => {
    const names = firstLayer?.fields.map((item) => item.name) ?? [];
    return names.find((name) => /name|title/i.test(name)) ?? names.find((name) => name !== suggestedId) ?? suggestedId;
  }, [firstLayer, suggestedId]);

  useEffect(() => {
    if (datasetImport.data?.state === "published") {
      void queryClient.invalidateQueries({ queryKey: ["dataset-versions", projectId] });
      void queryClient.invalidateQueries({ queryKey: ["quality", projectId] });
    }
  }, [datasetImport.data?.state, projectId, queryClient]);

  const mapping = useMutation({
    mutationFn: async (form: HTMLFormElement) => {
      const data = new FormData(form);
      const sourceId = formText(data, "source_id");
      const displayName = formText(data, "display_name");
      const targetKind = formText(data, "target_kind", "building") as MappingKind;
      if (!firstLayer) throw new Error("Отчёт инспекции ещё не готов");
      const fields: MappingDraft["fields"] = {};
      if (targetKind === "building") {
        fields.external_name = { source_field: displayName, transforms: [{ op: "trim" }] };
      }
      for (const field of requiredFieldsByKind[targetKind] ?? []) {
        const choice = formText(data, `required_${field.key}`);
        if (choice.startsWith("constant:")) {
          fields[field.key] = { source_field: null, transforms: [{ op: "constant", value: choice.slice(9) }] };
        } else if (choice.startsWith("source:")) {
          fields[field.key] = { source_field: choice.slice(7), transforms: [] };
        }
      }
      const body: MappingDraft = {
        profile_id: null,
        profile_name: formText(data, "profile_name", "Mapping"),
        layer_name: firstLayer.name,
        source_namespace: formText(data, "source_namespace", "uploaded"),
        target_kind: targetKind,
        source_id_field: sourceId,
        source_crs: formText(data, "source_crs", firstLayer.crs ?? ""),
        source_crs_confirmed: Boolean(data.get("source_crs_confirmed")),
        coordinate_columns: null,
        fields,
        missing_policy: formText(data, "missing_policy", "report_and_keep_null") as MappingDraft["missing_policy"],
        notes: "Настроено в web-мастере HeatRoute",
      };
      return saveImportMapping(importId, body);
    },
    onSuccess: async () => {
      await Promise.all([datasetImport.refetch(), report.refetch()]);
      toast.success("Сопоставление сохранено");
    },
    onError: (error) => toast.error("Сопоставление не сохранено", { description: errorText(error) }),
  });
  const validation = useMutation({
    mutationFn: () => validateImport(importId),
    onSuccess: async () => {
      await datasetImport.refetch();
      toast.success("Проверка запущена");
    },
    onError: (error) => toast.error("Проверка не запущена", { description: errorText(error) }),
  });
  const publication = useMutation({
    mutationFn: () => publishImport(importId, { confirm_quarantine: confirmQuarantine, coverage_limitations: coverageLimitations || null }),
    onSuccess: async () => {
      await datasetImport.refetch();
      toast.success("Публикация запущена");
    },
    onError: (error) => toast.error("Публикация заблокирована", { description: errorText(error) }),
  });

  function submitMapping(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    mapping.mutate(event.currentTarget);
  }

  if (datasetImport.isPending) return <StateView state="loading" title="Восстанавливаем импорт" detail="Состояние читается с сервера — reload не запустит новую задачу." />;
  if (datasetImport.isError) return <StateView state="error" title="Импорт недоступен" detail={errorText(datasetImport.error)} onRetry={() => void datasetImport.refetch()} />;

  return (
    <div className="page import-page">
      <header className="page-heading page-heading--compact">
        <div><Link className="back-link" to={`/projects/${projectId}/data`}><ArrowLeft size={15} /> К данным</Link><h1>Импорт {shortId(importId)}</h1><p>Создан {formatDate(datasetImport.data.created_at)} · job {shortId(datasetImport.data.job_id)}</p></div>
        <StatusBadge value={datasetImport.data.state} />
      </header>

      <ol className="stepper" aria-label="Этапы импорта">
        {steps.map(({ label, icon: Icon }, index) => {
          const number = index + 1;
          return <li key={label} className={number < activeStep || activeStep === 5 ? "is-complete" : number === activeStep ? "is-active" : ""}><span>{number < activeStep || activeStep === 5 ? <Check size={14} /> : <Icon size={15} />}</span><strong>{label}</strong></li>;
        })}
      </ol>

      {["uploaded", "inspecting", "validating", "publishing"].includes(datasetImport.data.state) && (
        <Card className="import-progress"><div><h2>{phaseTitle(datasetImport.data.phase)}</h2><p>Задача выполняется на сервере. Страница безопасно восстанавливается после перезагрузки.</p></div><ProgressBar label={phaseTitle(datasetImport.data.phase)} /></Card>
      )}

      {report.isPending && !["uploaded", "inspecting"].includes(datasetImport.data.state) && <StateView state="loading" title="Читаем отчёт" />}
      {report.isError && <StateView state="error" title="Отчёт пока недоступен" detail={errorText(report.error)} onRetry={() => void report.refetch()} />}

      {report.data && (
        <div className="import-grid">
          <section className="import-main">
            <Card className="inspection-card">
              <header><div><span className="card-icon"><FileSearch size={18} /></span><div><h2>Инспекция источника</h2><p>{displayValue(report.data.source_references[0]?.original_filename, "Raw-артефакт сохранён")} · provenance зафиксирован</p></div></div><StatusBadge value={report.data.import_state} /></header>
              <div className="inspection-metrics">
                <div><span>Слои</span><strong>{report.data.layers.length}</strong></div>
                <div><span>Объекты</span><strong>{formatNumber(report.data.layers.reduce((sum, layer) => sum + layer.feature_count, 0))}</strong></div>
                <div><span>Ошибки</span><strong>{report.data.errors.length}</strong></div>
                <div><span>Предупреждения</span><strong>{report.data.warnings.length}</strong></div>
              </div>
              {report.data.layers.map((layer) => (
                <div className="source-layer" key={layer.name}>
                  <div><strong>{layer.name}</strong><span>{layer.geometry_type ?? "Геометрия не определена"} · {layer.crs ?? "CRS не определена"} · {formatNumber(layer.feature_count)} объектов</span></div>
                  <div className="field-chips">{layer.fields.map((field) => <Badge key={field.name}>{field.name} · {field.dtype}</Badge>)}</div>
                  {layer.sample[0] && <details><summary>Пример исходной строки</summary><pre>{JSON.stringify(layer.sample[0], null, 2)}</pre></details>}
                </div>
              ))}
            </Card>

            {datasetImport.data.state === "mapping_required" && firstLayer && (
              <Card className="mapping-card">
                <header><div><h2>Сопоставление слоя</h2><p>Укажите происхождение, canonical kind и обязательные атрибуты.</p></div></header>
                <form onSubmit={submitMapping}>
                  <div className="form-grid">
                    <Field label="Название профиля"><input className="input" name="profile_name" defaultValue={`${firstLayer.name} mapping`} required /></Field>
                    <Field label="Namespace источника"><input className="input" name="source_namespace" defaultValue="uploaded" required /></Field>
                    <Field label="Canonical kind"><select className="select" name="target_kind" value={mappingKind} onChange={(event) => setMappingKind(event.target.value as MappingKind)}><option value="building">Здание</option><option value="road">Дорога</option><option value="utility_line">Инженерная сеть</option><option value="network_node">Узел сети</option><option value="network_edge">Ребро сети</option><option value="forbidden_zone">Запретная зона</option></select></Field>
                    <Field label="Source ID"><select className="select" name="source_id" defaultValue={suggestedId}>{firstLayer.fields.map((field) => <option key={field.name}>{field.name}</option>)}</select></Field>
                    {mappingKind === "building" && <Field label="Отображаемое имя"><select className="select" name="display_name" defaultValue={suggestedName}>{firstLayer.fields.map((field) => <option key={field.name}>{field.name}</option>)}</select></Field>}
                    <Field label="Source CRS"><input className="input" name="source_crs" defaultValue={firstLayer.crs ?? ""} required placeholder="Например, EPSG:4326" /></Field>
                    <Field label="Политика пропусков"><select className="select" name="missing_policy" defaultValue="report_and_keep_null"><option value="report_and_keep_null">Сохранить null и отразить в отчёте</option><option value="quarantine">Карантин</option><option value="reject">Отклонить строку</option></select></Field>
                    {(requiredFieldsByKind[mappingKind] ?? []).map((field) => (
                      <Field key={field.key} label={field.label}>
                        <select className="select" name={`required_${field.key}`} defaultValue={defaultRequiredChoice(field, firstLayer.fields.map((item) => item.name), suggestedId)}>
                          {(field.constants ?? []).map((value) => <option key={`constant:${value}`} value={`constant:${value}`}>Константа: {value}</option>)}
                          {firstLayer.fields.map((sourceField) => <option key={`source:${sourceField.name}`} value={`source:${sourceField.name}`}>Поле: {sourceField.name}</option>)}
                        </select>
                      </Field>
                    ))}
                  </div>
                  <label className="confirm-row"><input type="checkbox" name="source_crs_confirmed" required /><span><strong>Я подтверждаю CRS источника</strong><small>Система не угадывает CRS и порядок осей.</small></span></label>
                  <div className="card-actions"><Button type="submit" disabled={mapping.isPending}>{mapping.isPending ? "Сохраняем…" : <>Сохранить mapping <ArrowRight size={15} /></>}</Button></div>
                </form>
              </Card>
            )}

            {["ready_to_validate", "validating", "needs_review", "ready_to_publish", "publishing", "published"].includes(datasetImport.data.state) && (
              <Card className="validation-card">
                <header><div><h2>Отчёт проверки</h2><p>Ошибочные и quarantined строки не скрываются.</p></div><StatusBadge value={report.data.stage} /></header>
                <div className="inspection-metrics">
                  {Object.entries(report.data.counts).map(([key, value]) => <div key={key}><span>{countLabel(key)}</span><strong>{formatNumber(value)}</strong></div>)}
                </div>
                <div className="diagnostic-list">
                  {report.data.crs_diagnostics.map((item, index) => <CheckLine key={index} ok={!item.error}>{displayValue(item.message ?? item.code, "CRS проверена")}</CheckLine>)}
                  {report.data.topology_diagnostics.map((item, index) => <CheckLine key={index} ok={!item.blocking}>{displayValue(item.message ?? item.code, "Топология проверена")}</CheckLine>)}
                  {report.data.coverage_diagnostics.map((item, index) => <CheckLine key={index} ok={item.code !== "COVERAGE_UNKNOWN"}>{displayValue(item.message ?? item.code, "Покрытие")}</CheckLine>)}
                  {report.data.errors.map((item, index) => <CheckLine key={`e-${index}`} ok={false}>{displayValue(item.message ?? item.code, "Ошибка данных")}</CheckLine>)}
                </div>
                {datasetImport.data.state === "ready_to_validate" && <div className="card-actions"><Button onClick={() => validation.mutate()} disabled={validation.isPending}><ShieldCheck size={15} /> {validation.isPending ? "Запускаем…" : "Запустить validation"}</Button></div>}
              </Card>
            )}

            {["needs_review", "ready_to_publish", "publishing", "published"].includes(datasetImport.data.state) && (
              <Card className="publish-card">
                <header><div><h2>Публикация immutable version</h2><p>После подтверждения версия станет доступна редактору сценария.</p></div><Rocket size={18} /></header>
                <textarea className="textarea" value={coverageLimitations} onChange={(event) => setCoverageLimitations(event.target.value)} placeholder="Ограничения покрытия и применимости данных" />
                {(report.data.counts.quarantined ?? 0) > 0 && <label className="confirm-row"><input type="checkbox" checked={confirmQuarantine} onChange={(event) => setConfirmQuarantine(event.target.checked)} /><span><strong>Публиковать с карантином</strong><small>{report.data.counts.quarantined} строк останутся исключёнными и будут отражены в качестве.</small></span></label>}
                <div className="card-actions">
                  {datasetImport.data.state === "published" ? <Link className="button-link" to={`/projects/${projectId}/data`}>Открыть опубликованные данные <ArrowRight size={15} /></Link> : <Button onClick={() => publication.mutate()} disabled={publication.isPending || datasetImport.data.state === "publishing"}><Rocket size={15} /> {publication.isPending || datasetImport.data.state === "publishing" ? "Публикуем…" : "Опубликовать версию"}</Button>}
                </div>
              </Card>
            )}
          </section>
          <aside className="import-aside">
            <Card><h3>Publish blockers</h3>{report.data.publish_blockers.length ? report.data.publish_blockers.map((item, index) => <div className="blocker-row" key={index}><Badge tone="danger">{displayValue(item.code, "BLOCKER")}</Badge><p>{displayValue(item.message, "Требуется исправление")}</p></div>) : <p className="muted-copy">Блокирующих проблем нет.</p>}</Card>
            <Card><h3>Provenance</h3>{report.data.source_references.map((item, index) => <dl className="provenance-list" key={index}>{Object.entries(item).map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{String(value)}</dd></div>)}</dl>)}</Card>
          </aside>
        </div>
      )}
    </div>
  );
}

function phaseTitle(phase: string | null): string {
  const labels: Record<string, string> = { inspection: "Инспекция", inspection_complete: "Инспекция завершена", validation: "Проверка данных", publication: "Публикация" };
  return labels[phase ?? ""] ?? (phase?.replaceAll("_", " ") || "Подготовка");
}

function countLabel(key: string): string {
  const labels: Record<string, string> = { total: "Всего", read: "Прочитано", accepted: "Принято", quarantined: "Карантин", rejected: "Отклонено" };
  return labels[key] ?? key;
}
