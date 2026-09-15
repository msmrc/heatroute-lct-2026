import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AlertTriangle,
  Ban,
  Check,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  CircleStop,
  Clock3,
  Copy,
  Download,
  Eye,
  EyeOff,
  Layers3,
  MapPin,
  Play,
  Plus,
  RotateCcw,
  Save,
  SlidersHorizontal,
} from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { toast } from "sonner";

import { Button } from "../components/ui/button";
import { Badge, Field, Input, ProgressBar, StateView, StatusBadge } from "../components/ui/primitives";
import { WorkspaceMap } from "../features/map/WorkspaceMap";
import {
  cancelRun,
  createScenario,
  createScenarioRevision,
  fetchRun,
  fetchRunEvents,
  getProject,
  getScenario,
  listCostCatalogs,
  listDatasetVersions,
  listDatasetLayers,
  listRuleProfiles,
  listRuns,
  listScenarios,
  preflightRevision,
  runEventStreamUrl,
  runExportUrl,
  startRevisionRun,
  type CalculationRun,
  type Preflight,
} from "../shared/api";
import { displayValue, errorText, formatDate, formatMoney, formatNumber, shortId } from "../shared/format";
import { defaultScenarioDraft, draftFromSnapshot, type WorkspaceScenarioDraft } from "../shared/scenario";
import { type MapLayerKey, type WorkspacePanel, useUiStore } from "../shared/store";

const terminalStates = new Set(["succeeded", "partial", "failed", "cancelled"]);
const demoConstraintSequence: [number, number, number, number][] = [
  [37.623, 55.748, 37.628, 55.756],
  [37.6215, 55.7468, 37.6295, 55.7485],
  [37.616, 55.751, 37.621, 55.757],
];
const phaseLabels: Record<string, string> = {
  queued: "В очереди",
  preflight: "Предварительная проверка",
  preparation: "Подготовка графа",
  search: "Поиск маршрута",
  validation: "Проверка геометрии",
  evaluation: "Оценка вариантов",
  saving: "Сохранение",
  completed: "Готово",
  failed: "Ошибка",
  cancel_requested: "Отмена запрошена",
};

function asNumberPair(value: readonly number[] | null | undefined, fallback: readonly [number, number]): [number, number] {
  return value && value.length === 2 ? [Number(value[0]), Number(value[1])] : [...fallback];
}

export function WorkspacePage() {
  const { projectId = "" } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  const queryClient = useQueryClient();
  const selectedScenarioId = searchParams.get("scenario") ?? undefined;
  const runId = searchParams.get("run") ?? undefined;
  const [draft, setDraft] = useState<WorkspaceScenarioDraft>(() => defaultScenarioDraft(true));
  const [dirty, setDirty] = useState(false);
  const [loadedRevisionId, setLoadedRevisionId] = useState<string>();
  const [preflight, setPreflight] = useState<Preflight>();
  const [streamState, setStreamState] = useState<"idle" | "connected" | "reconnecting">("idle");
  const [mobilePanel, setMobilePanel] = useState<"scenario" | "inspector" | null>(null);
  const lastSequence = useRef(0);
  const collapsed = useUiStore((state) => state.collapsedPanels);
  const layers = useUiStore((state) => state.layers);
  const selectedAlternative = useUiStore((state) => state.selectedAlternative);
  const togglePanel = useUiStore((state) => state.togglePanel);
  const toggleLayer = useUiStore((state) => state.toggleLayer);
  const selectAlternative = useUiStore((state) => state.selectAlternative);

  const project = useQuery({
    queryKey: ["project", projectId],
    queryFn: ({ signal }) => getProject(projectId, signal),
    enabled: Boolean(projectId),
  });
  const scenarios = useQuery({
    queryKey: ["scenarios", projectId],
    queryFn: ({ signal }) => listScenarios(projectId, signal),
    enabled: Boolean(projectId),
  });
  const effectiveScenarioId = selectedScenarioId ?? scenarios.data?.[0]?.id;
  const scenario = useQuery({
    queryKey: ["scenario", projectId, effectiveScenarioId],
    queryFn: ({ signal }) => getScenario(effectiveScenarioId!, signal),
    enabled: Boolean(effectiveScenarioId),
  });
  const runs = useQuery({
    queryKey: ["runs", projectId],
    queryFn: ({ signal }) => listRuns(projectId, signal),
    enabled: Boolean(projectId),
  });
  const run = useQuery({
    queryKey: ["run", runId],
    queryFn: ({ signal }) => fetchRun(runId!, signal),
    enabled: Boolean(runId),
    refetchInterval: (query) => {
      const state = query.state.data?.job_state;
      return state && terminalStates.has(state) ? false : 1000;
    },
  });
  const events = useQuery({
    queryKey: ["run-events", runId],
    queryFn: ({ signal }) => fetchRunEvents(runId!, 0, signal),
    enabled: Boolean(runId),
    refetchInterval: run.data && !terminalStates.has(run.data.job_state) ? 1500 : false,
  });
  const datasetVersions = useQuery({
    queryKey: ["dataset-versions", projectId],
    queryFn: ({ signal }) => listDatasetVersions(projectId, signal),
    enabled: Boolean(projectId),
  });
  const publishedDatasetVersions = (datasetVersions.data ?? []).filter(
    (version) => version.status === "published",
  );
  const datasetLayerQueries = useQueries({
    queries: publishedDatasetVersions.map((version) => ({
      queryKey: ["dataset-layers", projectId, version.id],
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        listDatasetLayers(projectId, version.id, signal),
    })),
  });
  const mapDatasetLayers = datasetLayerQueries
    .flatMap((query) => query.data ?? [])
    .filter((layer) => layer.status === "published");
  const rules = useQuery({
    queryKey: ["rule-profiles", projectId],
    queryFn: ({ signal }) => listRuleProfiles(projectId, signal),
    enabled: Boolean(projectId),
  });
  const costs = useQuery({
    queryKey: ["cost-catalogs", projectId],
    queryFn: ({ signal }) => listCostCatalogs(projectId, signal),
    enabled: Boolean(projectId),
  });

  const latestRevision = scenario.data?.revisions.at(-1);
  const runState = run.data?.job_state;
  useEffect(() => {
    if (!latestRevision || latestRevision.id === loadedRevisionId) return;
    // A server revision replaces the local draft only when its immutable ID changes.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setDraft(draftFromSnapshot(latestRevision.input_snapshot));
    setLoadedRevisionId(latestRevision.id);
    setDirty(false);
  }, [latestRevision, loadedRevisionId]);

  useEffect(() => {
    if (!effectiveScenarioId || selectedScenarioId) return;
    setSearchParams((current) => {
      current.set("scenario", effectiveScenarioId);
      return current;
    }, { replace: true });
  }, [effectiveScenarioId, selectedScenarioId, setSearchParams]);

  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (!dirty) return;
      event.preventDefault();
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  useEffect(() => {
    if (!runId || !runState || terminalStates.has(runState)) {
      // The connection indicator mirrors the lifecycle of the authoritative run snapshot.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setStreamState("idle");
      return;
    }
    setStreamState("reconnecting");
    const stream = new EventSource(runEventStreamUrl(runId, lastSequence.current), { withCredentials: true });
    stream.onopen = () => setStreamState("connected");
    const handleEvent = (event: MessageEvent<string>) => {
      try {
        const raw: unknown = event.data;
        const parsed: unknown = typeof raw === "string" ? JSON.parse(raw) : undefined;
        const sequence = parsed && typeof parsed === "object" && "sequence" in parsed
          ? (parsed as { sequence?: unknown }).sequence
          : undefined;
        lastSequence.current = Math.max(lastSequence.current, typeof sequence === "number" ? sequence : 0);
      } catch {
        // A malformed progress message is ignored; the server snapshot remains authoritative.
      }
      void queryClient.invalidateQueries({ queryKey: ["run", runId] });
      void queryClient.invalidateQueries({ queryKey: ["run-events", runId] });
    };
    stream.onmessage = handleEvent;
    const namedEvents = ["run.queued", "run.started", "run.progress", "run.completed", "run.failed", "run.cancelled"];
    namedEvents.forEach((eventName) => stream.addEventListener(eventName, handleEvent as EventListener));
    stream.onerror = () => setStreamState("reconnecting");
    return () => {
      namedEvents.forEach((eventName) => stream.removeEventListener(eventName, handleEvent as EventListener));
      stream.close();
    };
  }, [queryClient, runId, runState]);

  useEffect(() => {
    if (!runId || !runState || !terminalStates.has(runState)) return;
    void queryClient.invalidateQueries({ queryKey: ["runs", projectId] });
  }, [projectId, queryClient, runId, runState]);

  const createScenarioMutation = useMutation({
    mutationFn: async () => {
      const created = await createScenario(projectId, `Сценарий ${(scenarios.data?.length ?? 0) + 1}`);
      await createScenarioRevision(created.id, 0, defaultScenarioDraft(true));
      return created;
    },
    onSuccess: async (created) => {
      await queryClient.invalidateQueries({ queryKey: ["scenarios", projectId] });
      setSearchParams({ scenario: created.id });
      toast.success("Сценарий создан");
    },
    onError: (error) => toast.error("Не удалось создать сценарий", { description: errorText(error) }),
  });

  const saveRevision = useMutation({
    mutationFn: async () => {
      if (!effectiveScenarioId) throw new Error("Сначала создайте сценарий");
      return createScenarioRevision(effectiveScenarioId, latestRevision?.revision ?? 0, draft);
    },
    onSuccess: async (revision) => {
      setLoadedRevisionId(revision.id);
      setDirty(false);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["scenario", projectId, effectiveScenarioId] }),
        queryClient.invalidateQueries({ queryKey: ["scenarios", projectId] }),
      ]);
      toast.success(`Сохранена revision ${revision.revision}`);
    },
    onError: (error) => {
      if ((error as { status?: number }).status === 412) {
        toast.error("Сценарий изменился на сервере", { description: "Обновите страницу и повторите правку поверх свежей revision." });
      } else {
        toast.error("Не удалось сохранить сценарий", { description: errorText(error) });
      }
    },
  });

  const calculate = useMutation({
    mutationFn: async () => {
      let revision = latestRevision;
      if (!effectiveScenarioId) throw new Error("Сначала создайте сценарий");
      if (!revision || dirty) {
        revision = await createScenarioRevision(effectiveScenarioId, latestRevision?.revision ?? 0, draft);
        setLoadedRevisionId(revision.id);
        setDirty(false);
      }
      const check = await preflightRevision(revision.id);
      setPreflight(check);
      if (!check.ready) throw new Error(check.findings.filter((item) => item.blocking).map((item) => item.message).join("; ") || "Preflight не пройден");
      return startRevisionRun(revision.id, "astar");
    },
    onSuccess: async (accepted) => {
      await queryClient.invalidateQueries({ queryKey: ["scenario", projectId, effectiveScenarioId] });
      setSearchParams((current) => {
        if (effectiveScenarioId) current.set("scenario", effectiveScenarioId);
        current.set("run", accepted.run_id);
        return current;
      });
      toast.success("Расчёт запущен", { description: `Job ${shortId(accepted.job_id)}` });
    },
    onError: (error) => toast.error("Расчёт не запущен", { description: errorText(error) }),
  });

  const cancel = useMutation({
    mutationFn: () => cancelRun(runId!),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["run", runId] });
      toast.message("Запрос на отмену отправлен");
    },
    onError: (error) => toast.error("Не удалось отменить расчёт", { description: errorText(error) }),
  });

  function patchDraft(patch: Partial<WorkspaceScenarioDraft>) {
    setDraft((current) => ({ ...current, ...patch }));
    setDirty(true);
    setPreflight(undefined);
  }

  function toggleObjective(objective: WorkspaceScenarioDraft["objective_profiles"][number]) {
    const selected = draft.objective_profiles.includes(objective);
    if (selected && draft.objective_profiles.length === 1) {
      toast.message("Нужна хотя бы одна цель оптимизации");
      return;
    }
    patchDraft({
      objective_profiles: selected
        ? draft.objective_profiles.filter((item) => item !== objective)
        : [...draft.objective_profiles, objective],
    });
  }

  function addConstraint() {
    const next = demoConstraintSequence[draft.forbidden_rectangles_wgs84.length];
    if (!next) {
      toast.message("Демо-набор ограничений уже добавлен");
      return;
    }
    patchDraft({ forbidden_rectangles_wgs84: [...draft.forbidden_rectangles_wgs84, next] });
    toast.message("Запретная зона добавлена в draft", { description: "Сохранение создаст новую revision." });
  }

  function openMobilePanel(panel: "scenario" | "inspector") {
    if (collapsed[panel]) togglePanel(panel);
    setMobilePanel((current) => current === panel ? null : panel);
  }

  const activeAlternative = run.data?.alternatives.find((item) => item.rank === selectedAlternative) ?? run.data?.alternatives[0];
  const isRunning = Boolean(run.data && !terminalStates.has(run.data.job_state));
  const selectedDatasetIds = new Set(draft.selected_dataset_version_ids);
  const jobPercentKnown = Boolean(run.data?.job_progress.total);
  const runScenarioRevisionId = typeof run.data?.versions_snapshot.scenario_revision_id === "string" ? run.data.versions_snapshot.scenario_revision_id : undefined;
  const staleRun = Boolean(runScenarioRevisionId && latestRevision && runScenarioRevisionId !== latestRevision.id);

  if (project.isPending || scenarios.isPending) return <StateView state="loading" title="Открываем проект" />;
  if (project.isError) return <StateView state="error" title="Проект недоступен" detail={errorText(project.error)} onRetry={() => void project.refetch()} />;

  return (
    <div className="workspace-page">
      <header className="workspace-header">
        <div className="workspace-title">
          <Link to="/projects">Проекты</Link><ChevronRight size={13} />
          <span>{project.data?.name}</span><ChevronRight size={13} />
          <select
            value={effectiveScenarioId ?? ""}
            onChange={(event) => setSearchParams({ scenario: event.target.value })}
            aria-label="Сценарий"
          >
            {!effectiveScenarioId && <option value="">Сценарий не выбран</option>}
            {(scenarios.data ?? []).map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
          </select>
          <ChevronDown size={13} />
          <button type="button" className="icon-button" title="Создать сценарий" onClick={() => createScenarioMutation.mutate()}><Plus size={16} /></button>
          {dirty ? <Badge tone="warning">Не сохранено</Badge> : latestRevision ? <Badge tone="neutral">rev {latestRevision.revision}</Badge> : null}
          {staleRun && <Badge tone="warning">Run от прошлой revision</Badge>}
        </div>
        <div className="workspace-actions">
          <div className="mobile-panel-actions">
            <Button variant="outline" aria-label="Параметры сценария" aria-pressed={mobilePanel === "scenario"} onClick={() => openMobilePanel("scenario")}><SlidersHorizontal size={15} /><span>Параметры</span></Button>
            <Button variant="outline" aria-label="Инспектор расчёта" aria-pressed={mobilePanel === "inspector"} onClick={() => openMobilePanel("inspector")}><Eye size={15} /><span>Инспектор</span></Button>
          </div>
          <Button variant="outline" disabled={!dirty || saveRevision.isPending} onClick={() => saveRevision.mutate()}>
            <Save size={15} /> {saveRevision.isPending ? "Сохраняем…" : "Сохранить"}
          </Button>
          {isRunning ? (
            <Button variant="outline" onClick={() => cancel.mutate()} disabled={cancel.isPending}><CircleStop size={15} /> Отменить расчёт</Button>
          ) : (
            <Button onClick={() => calculate.mutate()} disabled={!effectiveScenarioId || calculate.isPending}>
              <Play size={14} fill="currentColor" /> {calculate.isPending ? "Проверяем…" : "Рассчитать"}
            </Button>
          )}
        </div>
      </header>

      {!effectiveScenarioId ? (
        <StateView state="empty" title="В проекте нет сценариев" detail="Создайте сценарий — стартовые точки и безопасные демо-параметры будут заполнены автоматически." onRetry={() => createScenarioMutation.mutate()} />
      ) : (
        <div className={[
          "workspace-grid",
          collapsed.scenario && "is-left-collapsed",
          collapsed.inspector && "is-right-collapsed",
          collapsed.results && "is-bottom-collapsed",
        ].filter(Boolean).join(" ")}>
          {mobilePanel && <button type="button" className="mobile-panel-scrim" aria-label="Закрыть мобильную панель" onClick={() => setMobilePanel(null)} />}
          <aside className={`scenario-panel ${mobilePanel === "scenario" ? "is-mobile-open" : ""}`}>
            <PanelHeader title="Сценарий" panel="scenario" collapsed={collapsed.scenario} onToggle={(panel) => { togglePanel(panel); setMobilePanel(null); }} />
            {!collapsed.scenario && (
              <div className="panel-scroll">
                <section className="panel-section">
                  <h3><MapPin size={15} /> Геометрия</h3>
                  <div className="field-grid field-grid--2">
                    <Field label="Старт · долгота"><Input type="number" step="0.0001" value={draft.entry_point_wgs84[0]} onChange={(event) => patchDraft({ entry_point_wgs84: [Number(event.target.value), draft.entry_point_wgs84[1]] })} /></Field>
                    <Field label="Старт · широта"><Input type="number" step="0.0001" value={draft.entry_point_wgs84[1]} onChange={(event) => patchDraft({ entry_point_wgs84: [draft.entry_point_wgs84[0], Number(event.target.value)] })} /></Field>
                    <Field label="Финиш · долгота"><Input type="number" step="0.0001" value={asNumberPair(draft.goal_point_wgs84, [37.64, 55.752])[0]} onChange={(event) => patchDraft({ goal_point_wgs84: [Number(event.target.value), asNumberPair(draft.goal_point_wgs84, [37.64, 55.752])[1]] })} /></Field>
                    <Field label="Финиш · широта"><Input type="number" step="0.0001" value={asNumberPair(draft.goal_point_wgs84, [37.64, 55.752])[1]} onChange={(event) => patchDraft({ goal_point_wgs84: [asNumberPair(draft.goal_point_wgs84, [37.64, 55.752])[0], Number(event.target.value)] })} /></Field>
                  </div>
                  <Field label="Ширина коридора" hint="Окончательная геометрия проверяется сервером">
                    <div className="input-suffix"><Input type="number" min={0.1} max={50} step={0.1} value={draft.corridor_width_m} onChange={(event) => patchDraft({ corridor_width_m: Number(event.target.value) })} /><span>м</span></div>
                  </Field>
                  <button
                    type="button"
                    className={draft.forbidden_rectangles_wgs84.length ? "constraint-button is-active" : "constraint-button"}
                    onClick={() => patchDraft({ forbidden_rectangles_wgs84: draft.forbidden_rectangles_wgs84.slice(0, -1) })}
                  >
                    <Ban size={16} /><span><strong>{draft.forbidden_rectangles_wgs84.length ? `Запретных зон: ${draft.forbidden_rectangles_wgs84.length}` : "Запретных зон нет"}</strong><small>{draft.forbidden_rectangles_wgs84.length ? "Нажмите, чтобы удалить последнюю" : "Добавьте зону инструментом карты"}</small></span>
                  </button>
                </section>

                <section className="panel-section">
                  <h3><SlidersHorizontal size={15} /> Поиск</h3>
                  <Field label="Режим проверки">
                    <select className="select" value={draft.validation_mode} onChange={(event) => patchDraft({ validation_mode: event.target.value as "strict" | "exploratory" })}>
                      <option value="strict">Строгий — неизвестные критичные данные блокируют</option>
                      <option value="exploratory">Исследовательский — явные допущения</option>
                    </select>
                  </Field>
                  <Field label="Дата планирования" hint="Фиксируется в snapshot и проверяет temporal validity данных">
                    <Input type="date" value={draft.planning_date ?? ""} onChange={(event) => patchDraft({ planning_date: event.target.value || null })} />
                  </Field>
                  <div className="field-grid field-grid--2">
                    <Field label="Шаг сетки"><div className="input-suffix"><Input type="number" min={2} max={100} value={draft.search_settings.resolution_m} onChange={(event) => patchDraft({ search_settings: { ...draft.search_settings, resolution_m: Number(event.target.value) } })} /><span>м</span></div></Field>
                    <Field label="Макс. вариантов"><Input type="number" min={1} max={5} value={draft.search_settings.max_alternatives} onChange={(event) => patchDraft({ search_settings: { ...draft.search_settings, max_alternatives: Number(event.target.value) } })} /></Field>
                  </div>
                  <Field label="Метод строительства">
                    <select className="select" value={draft.construction_methods[0] ?? "open_trench"} onChange={(event) => patchDraft({ construction_methods: [event.target.value] })}>
                      <option value="open_trench">Открытая траншея</option>
                      <option value="horizontal_directional_drilling">ГНБ</option>
                      <option value="microtunneling">Микротоннелирование</option>
                      <option value="pipe_jacking">Продавливание футляра</option>
                      <option value="bridge_attachment">Подвеска к мосту</option>
                      <option value="existing_duct">Существующий канал / коллектор</option>
                    </select>
                  </Field>
                  <div className="dataset-choice objective-choice">
                    <span>Цели оптимизации</span>
                    <label><input type="checkbox" checked={draft.objective_profiles.includes("shortest")} onChange={() => toggleObjective("shortest")} /><span>Кратчайший путь<small>Минимальная длина коридора</small></span></label>
                    <label><input type="checkbox" checked={draft.objective_profiles.includes("least_unverified")} onChange={() => toggleObjective("least_unverified")} /><span>Меньше неизвестного<small>Штраф за неполные данные</small></span></label>
                    <label title={draft.cost_catalog_version_id ? undefined : "Сначала выберите каталог стоимости"}><input type="checkbox" disabled={!draft.cost_catalog_version_id} checked={draft.objective_profiles.includes("estimated_cost")} onChange={() => toggleObjective("estimated_cost")} /><span>Расчётная стоимость<small>{draft.cost_catalog_version_id ? "По ставкам выбранной версии" : "Нужен каталог стоимости"}</small></span></label>
                  </div>
                </section>

                <section className="panel-section">
                  <h3><Layers3 size={15} /> Версии</h3>
                  <Field label="Правила">
                    <select className="select" value={draft.rule_profile_version_id ?? ""} onChange={(event) => patchDraft({ rule_profile_version_id: event.target.value || null })}>
                      <option value="">Без профиля · demo</option>
                      {(rules.data ?? []).flatMap((profile) => profile.versions.map((version) => <option key={version.id} value={version.id}>{profile.name} · rev {version.revision} · {version.status}</option>))}
                    </select>
                  </Field>
                  <Field label="Каталог стоимости">
                    <select className="select" value={draft.cost_catalog_version_id ?? ""} onChange={(event) => {
                      const value = event.target.value || null;
                      patchDraft({
                        cost_catalog_version_id: value,
                        objective_profiles: value ? draft.objective_profiles : draft.objective_profiles.filter((item) => item !== "estimated_cost"),
                      });
                    }}>
                      <option value="">Без стоимости</option>
                      {(costs.data ?? []).flatMap((catalog) => catalog.versions.map((version) => <option key={version.id} value={version.id}>{catalog.name} · rev {version.revision} · {version.status}</option>))}
                    </select>
                  </Field>
                  <div className="dataset-choice">
                    <span>Версии данных</span>
                    {(datasetVersions.data ?? []).filter((item) => item.status === "published").map((version) => (
                      <label key={version.id}>
                        <input type="checkbox" checked={selectedDatasetIds.has(version.id)} onChange={() => patchDraft({ selected_dataset_version_ids: selectedDatasetIds.has(version.id) ? draft.selected_dataset_version_ids.filter((id) => id !== version.id) : [...draft.selected_dataset_version_ids, version.id] })} />
                        <span>{version.source_name}<small>v{version.version} · {version.working_crs ?? "CRS?"}</small></span>
                      </label>
                    ))}
                    {(datasetVersions.data ?? []).filter((item) => item.status === "published").length === 0 && <small>Опубликованных поставок нет — доступен синтетический расчёт.</small>}
                  </div>
                </section>
              </div>
            )}
          </aside>

          <section className="map-panel">
            <div className="map-commandbar">
              <div className="map-tools" role="toolbar" aria-label="Инструменты карты">
                <button type="button" className="is-active" title="Выбор объекта"><MapPin size={16} /></button>
                <button type="button" title="Добавить запретную зону" onClick={addConstraint}><Ban size={16} /></button>
                <button type="button" title="Обновить данные карты" onClick={() => void run.refetch()}><RotateCcw size={16} /></button>
              </div>
              <div className="layer-toggles">
                {(Object.keys(layers) as MapLayerKey[]).map((key) => (
                  <button key={key} type="button" className={layers[key] ? "is-active" : ""} aria-pressed={layers[key]} onClick={() => toggleLayer(key)}>
                    {layers[key] ? <Eye size={13} /> : <EyeOff size={13} />} {layerLabels[key]}
                  </button>
                ))}
              </div>
            </div>
            <div className="workspace-map-wrap">
              <WorkspaceMap
                alternatives={run.data?.alternatives}
                selectedRank={activeAlternative?.rank}
                draft={draft}
                layers={layers}
                tileLayers={mapDatasetLayers}
              />
              {run.isError && <div className="map-overlay-card is-error"><AlertTriangle size={17} /><span><strong>Run недоступен</strong><small>{errorText(run.error)}</small></span></div>}
              {isRunning && run.data && (
                <div className="map-overlay-card run-progress-card">
                  <div><span className="live-dot" /><strong>{phaseLabels[run.data.phase] ?? run.data.phase}</strong><StatusBadge value={run.data.job_state} /></div>
                  <ProgressBar current={numericValue(run.data.job_progress.current)} total={numericValue(run.data.job_progress.total)} label={jobPercentKnown ? "Прогресс" : "Этап выполняется"} />
                  <small>{streamState === "connected" ? `Live-события подключены · ${events.data?.length ?? 0} событий` : streamState === "reconnecting" ? "Переподключаем live-события · polling работает" : "Серверный snapshot"}</small>
                </div>
              )}
              {!runId && <div className="map-empty-workspace"><span><Play size={20} /></span><strong>Готово к первому расчёту</strong><p>Проверьте параметры слева и запустите backend-маршрутизацию.</p></div>}
            </div>
          </section>

          <aside className={`inspector-panel ${mobilePanel === "inspector" ? "is-mobile-open" : ""}`}>
            <PanelHeader title="Инспектор" panel="inspector" collapsed={collapsed.inspector} onToggle={(panel) => { togglePanel(panel); setMobilePanel(null); }} />
            {!collapsed.inspector && <Inspector projectId={projectId} run={run.data} preflight={preflight} />}
          </aside>

          <section className="results-panel">
            <PanelHeader title="Варианты и история" panel="results" collapsed={collapsed.results} onToggle={togglePanel} horizontal />
            {!collapsed.results && (
              <div className="results-body">
                <div className="alternatives-strip">
                  {(run.data?.alternatives ?? []).map((alternative) => (
                    <button key={alternative.id} type="button" className={activeAlternative?.id === alternative.id ? "alternative-card is-selected" : "alternative-card"} onClick={() => selectAlternative(alternative.rank)}>
                      <span className="alternative-letter">{String.fromCharCode(64 + alternative.rank)}</span>
                      <span><strong>{alternative.objective_tags.join(" + ") || "Маршрут"}</strong><small>{formatNumber(alternative.metrics.route_length_m, 1)} м</small></span>
                      <span><b>{formatMoney(alternative.cost_breakdown.total)}</b><StatusBadge value={displayValue(alternative.cost_breakdown.status, "unknown")} /><StatusBadge value={alternative.geometry_status} /></span>
                    </button>
                  ))}
                  {run.data && run.data.alternatives.length === 0 && <div className="result-diagnostic"><AlertTriangle size={17} /><span><strong>Маршрут не найден</strong><small>Outcome: {run.data.outcome}. Проверьте budget, ограничения и точки подключения.</small></span></div>}
                  {!run.data && <StateView compact state="empty" title="Варианты появятся после расчёта" />}
                </div>
                <div className="history-list">
                  <h3>Последние runs</h3>
                  {(runs.data ?? []).slice(0, 5).map((item) => (
                    <button key={item.id} type="button" className={item.id === runId ? "history-row is-active" : "history-row"} onClick={() => setSearchParams((current) => { current.set("run", item.id); return current; })}>
                      <span className="history-row__id"><Clock3 size={14} /><strong>{shortId(item.id)}</strong></span>
                      <span className="history-row__date">{formatDate(item.created_at)}</span>
                      <span className="history-row__metrics">{item.alternatives_count} вар. · {formatNumber(item.route_length_m, 1)} м</span>
                      <StatusBadge value={item.job_state} />
                    </button>
                  ))}
                </div>
              </div>
            )}
          </section>
        </div>
      )}
    </div>
  );
}

const layerLabels: Record<MapLayerKey, string> = {
  constraints: "Ограничения",
  datasets: "Данные",
  routes: "Трассы",
  corridors: "Коридоры",
  endpoints: "Точки",
  findings: "Findings",
};

function PanelHeader({
  title,
  panel,
  collapsed,
  onToggle,
  horizontal = false,
}: {
  title: string;
  panel: WorkspacePanel;
  collapsed: boolean;
  onToggle: (panel: WorkspacePanel) => void;
  horizontal?: boolean;
}) {
  return (
    <header className="panel-header">
      {!collapsed && <strong>{title}</strong>}
      <button type="button" className="icon-button" onClick={() => onToggle(panel)} aria-label={collapsed ? `Развернуть панель «${title}»` : `Свернуть панель «${title}»`}>
        {horizontal ? <ChevronDown className={collapsed ? "is-rotated" : ""} size={15} /> : collapsed ? <ChevronRight size={15} /> : <ChevronLeft size={15} />}
      </button>
    </header>
  );
}

function Inspector({ projectId, run, preflight }: { projectId: string; run?: CalculationRun; preflight?: Preflight }) {
  const [tab, setTab] = useState<"checks" | "passport">("checks");
  const alternative = run?.alternatives[0];
  const checks = alternative?.validation_report;
  const copyId = (value: string) => void navigator.clipboard.writeText(value).then(() => toast.success("ID скопирован"));
  return (
    <div className="inspector-body">
      <div className="mini-tabs" role="tablist">
        <button type="button" className={tab === "checks" ? "is-active" : ""} onClick={() => setTab("checks")}>Проверки</button>
        <button type="button" className={tab === "passport" ? "is-active" : ""} onClick={() => setTab("passport")}>Паспорт</button>
      </div>
      {!run ? (
        <StateView compact state="empty" title="Выберите или запустите run" detail="Здесь появятся источники, версии, проверки и неизвестные поля." />
      ) : tab === "checks" ? (
        <>
          {run.job_state === "partial" && <div className="notice notice--warning"><AlertTriangle size={15} /><span><strong>Частичный результат</strong><small>Поиск остановлен по лимиту; показаны только проверенные найденные варианты.</small></span></div>}
          <div className="inspector-summary">
            <div><span>Outcome</span><StatusBadge value={run.outcome} /></div>
            <div><span>Геометрия</span><StatusBadge value={alternative?.geometry_status ?? "unknown"} /></div>
            <div><span>Покрытие поиска</span><b>{run.search_completion.replaceAll("_", " ")}</b></div>
          </div>
          <section className="inspector-section"><h3>Выполненные проверки</h3>
            <CheckRow ok={Boolean(alternative)} label="Геометрия коридора" detail={displayValue(checks?.status ?? alternative?.geometry_status, "Ожидает")} />
            <CheckRow ok={false} label="Гидравлика" detail="Недостаточно исходных данных" />
            <CheckRow ok={false} label="Вертикальные пересечения" detail="Глубины и отметки отсутствуют" />
          </section>
          {(preflight?.findings ?? []).length > 0 && <section className="inspector-section"><h3>Preflight findings</h3>{preflight?.findings.map((item) => <div className="finding-row" key={item.code}><StatusBadge value={item.severity} /><span><strong>{item.code}</strong><small>{item.message}</small></span></div>)}</section>}
          <section className="inspector-section"><h3>Допущения</h3>{run.assumptions.length ? run.assumptions.map((item, index) => <p className="assumption" key={index}>{typeof item === "string" ? item : JSON.stringify(item)}</p>) : <p className="muted-copy">Явных допущений нет.</p>}</section>
        </>
      ) : (
        <>
          <section className="inspector-section passport-list"><h3>Идентификаторы</h3>
            <CopyRow label="Run" value={run.id} onCopy={copyId} />
            <CopyRow label="Job" value={run.job_id} onCopy={copyId} />
            <CopyRow label="Algorithm" value={`${run.algorithm_name} · ${run.algorithm_version}`} onCopy={copyId} />
          </section>
          <section className="inspector-section passport-list"><h3>Воспроизводимость</h3>
            {Object.entries(run.versions_snapshot).map(([key, value]) => <div key={key}><span>{key.replaceAll("_", " ")}</span><code>{displayValue(value)}</code></div>)}
            <div><span>Runtime</span><code>{Object.entries(run.runtime_library_versions).map(([key, value]) => `${key} ${displayValue(value)}`).join(" · ") || "—"}</code></div>
          </section>
          <section className="inspector-section"><h3>Выгрузки сервера</h3><div className="export-grid">
            {(["geojson", "html", "json", "csv"] as const).map((format) => <a key={format} className="export-link" href={runExportUrl(run.id, format)} target={format === "html" ? "_blank" : undefined} rel="noreferrer"><Download size={14} /> {format.toUpperCase()}</a>)}
          </div><Link className="full-passport-link" to={`/projects/${projectId}/runs/${run.id}`}>Открыть полный паспорт <ChevronRight size={14} /></Link></section>
        </>
      )}
    </div>
  );
}

function CheckRow({ ok, label, detail }: { ok: boolean; label: string; detail: string }) {
  return <div className="check-row"><span className={ok ? "check-dot is-ok" : "check-dot"}>{ok ? <Check size={13} /> : "?"}</span><span><strong>{label}</strong><small>{detail}</small></span></div>;
}

function CopyRow({ label, value, onCopy }: { label: string; value: string; onCopy: (value: string) => void }) {
  return <div><span>{label}</span><button type="button" onClick={() => onCopy(value)}><code>{value}</code><Copy size={13} /></button></div>;
}

function numericValue(value: unknown): number | null {
  return typeof value === "number" ? value : null;
}
