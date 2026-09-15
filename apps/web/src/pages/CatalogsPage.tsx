import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Download, Layers3, Plus, ReceiptText, Save } from "lucide-react";
import { useMemo, useState } from "react";
import { useLocation, useSearchParams } from "react-router-dom";
import { toast } from "sonner";

import { Button } from "../components/ui/button";
import { Badge, Card, Dialog, Field, Input, StateView, StatusBadge } from "../components/ui/primitives";
import {
  costCatalogExportUrl,
  createCostCatalog,
  createRuleProfile,
  listCostCatalogs,
  listProjects,
  listRuleProfiles,
  reviseCostCatalog,
  reviseRuleProfile,
  type CostCatalog,
  type RuleProfile,
} from "../shared/api";
import { errorText, formText, formatDate, shortId } from "../shared/format";

export function CatalogsPage() {
  const isCosts = useLocation().pathname.endsWith("/costs");
  const [params, setParams] = useSearchParams();
  const [createOpen, setCreateOpen] = useState(false);
  const projects = useQuery({ queryKey: ["projects"], queryFn: ({ signal }) => listProjects(signal) });
  const projectId = params.get("project") ?? projects.data?.slice().sort((a, b) => b.updated_at.localeCompare(a.updated_at))[0]?.id;
  const queryClient = useQueryClient();
  const rules = useQuery({ queryKey: ["rule-profiles", projectId], queryFn: ({ signal }) => listRuleProfiles(projectId!, signal), enabled: Boolean(projectId && !isCosts) });
  const costs = useQuery({ queryKey: ["cost-catalogs", projectId], queryFn: ({ signal }) => listCostCatalogs(projectId!, signal), enabled: Boolean(projectId && isCosts) });
  const items = isCosts ? costs.data : rules.data;
  const pending = isCosts ? costs.isPending : rules.isPending;
  const queryError = isCosts ? costs.error : rules.error;

  const createMutation = useMutation({
    mutationFn: async (name: string) => {
      if (!projectId) throw new Error("Выберите проект");
      return isCosts
        ? createCostCatalog(projectId, { name, definition: demoCostDefinition(name, 1) })
        : createRuleProfile(projectId, { name, definition: demoRuleDefinition(name, 1) });
    },
    onSuccess: async () => {
      setCreateOpen(false);
      await queryClient.invalidateQueries({ queryKey: [isCosts ? "cost-catalogs" : "rule-profiles", projectId] });
      toast.success(isCosts ? "Каталог стоимости создан" : "Профиль правил создан");
    },
    onError: (error) => toast.error("Не удалось создать версию", { description: errorText(error) }),
  });
  const revise = useMutation({
    mutationFn: async (item: CostCatalog | RuleProfile) => {
      const current = item.versions.at(-1);
      if (!current) throw new Error("Текущая версия не найдена");
      const definition = isCosts
        ? { ...current.definition, version: item.current_revision + 1, estimate_status: "draft" }
        : { ...current.definition, version: item.current_revision + 1, status: "draft" };
      return isCosts ? reviseCostCatalog(item as CostCatalog, definition) : reviseRuleProfile(item as RuleProfile, definition);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: [isCosts ? "cost-catalogs" : "rule-profiles", projectId] });
      toast.success("Создана новая draft version");
    },
    onError: (error) => toast.error((error as { status?: number }).status === 412 ? "Версия устарела — обновите список" : "Новая версия не создана", { description: errorText(error) }),
  });

  const title = isCosts ? "Каталоги стоимости" : "Профили правил";
  const Icon = isCosts ? ReceiptText : Layers3;
  const latestItems = useMemo(() => items ?? [], [items]);
  return (
    <div className="page catalogs-page">
      <header className="page-heading"><div><span className="eyebrow">Immutable versions</span><h1>{title}</h1><p>Published/reviewed определения не меняются на месте: любая правка создаёт новую version.</p></div><Button onClick={() => setCreateOpen(true)} disabled={!projectId}><Plus size={15} /> Создать</Button></header>
      <div className="page-toolbar"><select className="select compact-select" value={projectId ?? ""} onChange={(event) => setParams({ project: event.target.value })}><option value="">Выберите проект</option>{(projects.data ?? []).slice().sort((a, b) => b.updated_at.localeCompare(a.updated_at)).slice(0, 60).map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}</select><span>{latestItems.length} профилей</span></div>
      {pending && <StateView state="loading" title="Загружаем версии" />}
      {queryError && <StateView state="error" title="Каталоги недоступны" detail={errorText(queryError)} />}
      <div className="catalog-grid">
        {latestItems.map((item) => {
          const current = item.versions.at(-1);
          return <Card className="catalog-card" key={item.id}><header><span className="catalog-icon"><Icon size={18} /></span><div><h2>{item.name}</h2><p>{shortId(item.id)} · обновлён {formatDate(item.updated_at)}</p></div><StatusBadge value={current?.status ?? "unknown"} /></header><div className="catalog-meta"><div><span>Текущая revision</span><strong>{item.current_revision}</strong></div><div><span>Всего версий</span><strong>{item.versions.length}</strong></div><div><span>Hash</span><code>{current?.definition_hash.slice(0, 10) ?? "—"}</code></div></div><div className="version-timeline">{item.versions.map((version) => <span key={version.id}><Badge tone={version === current ? "violet" : "neutral"}>rev {version.revision}</Badge><small>{version.status}</small></span>)}</div><footer><Button variant="outline" onClick={() => revise.mutate(item)} disabled={revise.isPending}><Save size={14} /> Новая draft</Button>{isCosts && <div className="export-actions">{(["json", "csv", "html"] as const).map((format) => <a key={format} href={costCatalogExportUrl(item.id, format)} aria-label={`Скачать ${format}`}><Download size={14} /> {format}</a>)}</div>}</footer></Card>;
        })}
      </div>
      {!pending && latestItems.length === 0 && <StateView state="empty" title={isCosts ? "Каталогов стоимости нет" : "Профилей правил нет"} detail="Создайте стартовую demo version; она останется явно помеченной как synthetic/draft." />}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} title={isCosts ? "Новый каталог стоимости" : "Новый профиль правил"} description="Создастся минимальная синтетическая version 1 — значения не являются нормативными или рыночными.">
        <form className="dialog-form" onSubmit={(event) => { event.preventDefault(); createMutation.mutate(formText(new FormData(event.currentTarget), "name")); }}>
          <Field label="Название"><Input name="name" defaultValue={isCosts ? "Демо-стоимость" : "Демо-правила"} required autoFocus /></Field>
          <div className="notice notice--warning"><span><strong>Синтетические значения</strong><small>Перед реальным применением создайте reviewed version с источниками.</small></span></div>
          <div className="dialog-actions"><Button type="button" variant="ghost" onClick={() => setCreateOpen(false)}>Отмена</Button><Button type="submit" disabled={createMutation.isPending}>Создать version 1</Button></div>
        </form>
      </Dialog>
    </div>
  );
}

function demoRuleDefinition(name: string, version: number): Record<string, unknown> {
  return {
    schema_version: "1.0",
    id: crypto.randomUUID(),
    version,
    name,
    status: "demo",
    source_references: ["Создано в HeatRoute UI; синтетическое значение"],
    missing_data_policy: { strict: "block_critical_unknowns", exploratory: "only_explicit_assumptions" },
    geometry_tolerances: { precision_m: 0.01, snap_tolerance_m: 0.25, touch_policy: "forbid_except_named_entry_contact" },
    rules: [{ id: "UI-DEMO-EXCLUSION", type: "hard_exclusion", applies_to_kind: "forbidden_zone", parameters: { clearance_m: 0 }, severity: "blocker", missing_policy: "block", source_reference: "synthetic:user_constraint" }],
  };
}

function demoCostDefinition(name: string, version: number): Record<string, unknown> {
  return {
    schema_version: "1.0",
    id: crypto.randomUUID(),
    version,
    name,
    currency: "RUB",
    price_date: new Date().toISOString().slice(0, 10),
    estimate_status: "synthetic",
    region_scope: "fictional_demo_only",
    tax_policy: "excluded_and_not_estimated",
    rounding_policy: "ROUND_HALF_UP_2_DECIMALS",
    exclusions: ["Не является сметой или рыночной ценой"],
    items: [
      { code: "UI-DEMO-TRENCH", description: "Синтетическая ставка за метр коридора", quantity_unit: "m", per: "corridor_m", applies_to_method: "open_trench", rate: "10000.00", source_reference: "synthetic" },
      { code: "UI-DEMO-PIPE", description: "Синтетическая ставка за метр трубы", quantity_unit: "m", per: "pipe_m", applies_to_method: "all", rate: "6500.00", source_reference: "synthetic" },
      { code: "UI-DEMO-BEND", description: "Синтетическая ставка за поворот", quantity_unit: "item", per: "item", applies_to_method: "all", rate: "15000.00", source_reference: "synthetic" },
      { code: "UI-DEMO-TIE-IN", description: "Синтетическая ставка за врезку", quantity_unit: "event", per: "event", applies_to_method: "demo_tie_in", rate: "250000.00", source_reference: "synthetic" },
      { code: "UI-DEMO-RESTORATION", description: "Синтетическая ставка восстановления покрытия", quantity_unit: "m2", per: "m2", applies_to_method: "open_trench", rate: "3200.00", source_reference: "synthetic" },
    ],
  };
}
