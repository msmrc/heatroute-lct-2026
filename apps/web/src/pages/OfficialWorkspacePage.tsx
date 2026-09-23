import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, Download, Eye, FileJson2, FlaskConical, LoaderCircle, Play, RotateCcw, UploadCloud, XCircle } from "lucide-react";
import { type ChangeEvent, useEffect, useRef, useState } from "react";
import { toast } from "sonner";

import { Card, ProgressBar, StateView, StatusBadge } from "../components/ui/primitives";
import { Button } from "../components/ui/button";
import { RouteVisualization } from "../components/official/RouteVisualization";
import {
  ApiError,
  cancelOfficialJob,
  createOfficialDemoImport,
  createOfficialImport,
  createOfficialRun,
  createTopologyJob,
  getOfficialImport,
  getOfficialJob,
  getLatestOfficialRun,
  getOfficialRun,
  humanFileSize,
  officialExportUrl,
  type OfficialRun,
  type RoutingAlgorithmProfile,
} from "../shared/api";

const IMPORT_KEY = "heatroute.officialImportId";
const JOB_KEY = "heatroute.officialJobId";
const RUN_KEY = "heatroute.officialRunId";
const STABLE_RUN_KEY = "heatroute.officialStableRunId";
const EXPERIMENTAL_RUN_KEY = "heatroute.officialExperimentalRunId";

function runProfile(run: OfficialRun): RoutingAlgorithmProfile {
  return run.parameters?.algorithm_profile ?? "stable";
}

function profileName(profile: RoutingAlgorithmProfile): string {
  return profile === "expert_experimental" ? "Экспериментальный" : "Основной";
}

function errorText(error: unknown): string {
  return error instanceof ApiError ? error.message : error instanceof Error ? error.message : "Неизвестная ошибка";
}

export function OfficialWorkspacePage() {
  const queryClient = useQueryClient();
  const inputRef = useRef<HTMLInputElement>(null);
  const [importId, setImportId] = useState(() => localStorage.getItem(IMPORT_KEY) ?? "");
  const [jobId, setJobId] = useState(() => localStorage.getItem(JOB_KEY) ?? "");
  const [runId, setRunId] = useState(() => localStorage.getItem(RUN_KEY) ?? "");
  const [processingFilename, setProcessingFilename] = useState("");
  const stableRunId = localStorage.getItem(STABLE_RUN_KEY) ?? "";
  const experimentalRunId = localStorage.getItem(EXPERIMENTAL_RUN_KEY) ?? "";

  function rememberRun(value: OfficialRun, profile: RoutingAlgorithmProfile) {
    localStorage.setItem(RUN_KEY, value.id);
    setRunId(value.id);
    if (profile === "expert_experimental") {
      localStorage.setItem(EXPERIMENTAL_RUN_KEY, value.id);
    } else {
      localStorage.setItem(STABLE_RUN_KEY, value.id);
    }
  }

  function openRun(id: string) {
    localStorage.setItem(RUN_KEY, id);
    localStorage.removeItem(JOB_KEY);
    setRunId(id);
    setJobId("");
  }

  const imported = useQuery({
    queryKey: ["official-import", importId],
    queryFn: ({ signal }) => getOfficialImport(importId, signal),
    enabled: Boolean(importId),
    retry: false,
  });
  const job = useQuery({
    queryKey: ["official-job", jobId],
    queryFn: ({ signal }) => getOfficialJob(jobId, signal),
    enabled: Boolean(jobId),
    retry: false,
    refetchInterval: (query) => {
      const state = query.state.data?.state;
      return state === "queued" || state === "running" || state === "cancel_requested" ? 1_000 : false;
    },
  });
  const run = useQuery({
    queryKey: ["official-run", runId],
    queryFn: ({ signal }) => getOfficialRun(runId, signal),
    enabled: Boolean(runId),
    retry: false,
    refetchInterval: (query) => {
      const state = query.state.data?.state;
      return state === "queued" || state === "running" ? 1_000 : false;
    },
  });

  const upload = useMutation({
    mutationFn: async (file: File) => {
      const importedValue = await createOfficialImport(file);
      if (!importedValue.report.valid) return { importedValue };
      try {
        const runValue = await createOfficialRun(importedValue.id);
        return { importedValue, runValue };
      } catch (runError) {
        return { importedValue, runError };
      }
    },
    onSuccess: ({ importedValue, runValue, runError }) => {
      localStorage.setItem(IMPORT_KEY, importedValue.id);
      localStorage.removeItem(JOB_KEY);
      localStorage.removeItem(RUN_KEY);
      localStorage.removeItem(STABLE_RUN_KEY);
      localStorage.removeItem(EXPERIMENTAL_RUN_KEY);
      setImportId(importedValue.id);
      setJobId("");
      setRunId("");
      queryClient.setQueryData(["official-import", importedValue.id], importedValue);

      if (runValue) {
        rememberRun(runValue, "stable");
        queryClient.setQueryData(["official-run", runValue.id], runValue);
        if (runValue.job_id) {
          localStorage.setItem(JOB_KEY, runValue.job_id);
          setJobId(runValue.job_id);
        }
        toast.success("Файл принят, расчёт запущен");
      } else if (runError) {
        toast.error(`Файл загружен, но расчёт не запущен: ${errorText(runError)}`);
      } else {
        toast.error("В файле есть ошибки. Исправьте их перед расчётом.");
      }
    },
    onError: (error) => toast.error(errorText(error)),
  });
  const loadDemo = useMutation({
    mutationFn: async () => {
      try {
        return { run: await getLatestOfficialRun() };
      } catch (error) {
        if (!(error instanceof ApiError) || error.status !== 404) throw error;
        return { imported: await createOfficialDemoImport() };
      }
    },
    onSuccess: ({ run: value, imported: demoImport }) => {
      if (demoImport) {
        localStorage.setItem(IMPORT_KEY, demoImport.id);
        localStorage.removeItem(JOB_KEY);
        localStorage.removeItem(RUN_KEY);
        localStorage.removeItem(STABLE_RUN_KEY);
        localStorage.removeItem(EXPERIMENTAL_RUN_KEY);
        setImportId(demoImport.id);
        setJobId("");
        setRunId("");
        queryClient.setQueryData(["official-import", demoImport.id], demoImport);
        toast.success("Демо-набор открыт — выберите основной или экспериментальный алгоритм");
        return;
      }
      if (!value) return;
      if (localStorage.getItem(IMPORT_KEY) !== value.import_id) {
        localStorage.removeItem(STABLE_RUN_KEY);
        localStorage.removeItem(EXPERIMENTAL_RUN_KEY);
      }
      localStorage.setItem(IMPORT_KEY, value.import_id);
      setImportId(value.import_id);
      rememberRun(value, runProfile(value));
      queryClient.setQueryData(["official-run", value.id], value);
      if (value.job_id) {
        localStorage.setItem(JOB_KEY, value.job_id);
        setJobId(value.job_id);
      } else {
        localStorage.removeItem(JOB_KEY);
        setJobId("");
      }
      toast.success("Готовый расчёт открыт");
    },
    onError: (error) => toast.error(errorText(error)),
  });
  const startJob = useMutation({
    mutationFn: () => createTopologyJob(importId),
    onSuccess: (value) => {
      localStorage.setItem(JOB_KEY, value.id);
      localStorage.removeItem(RUN_KEY);
      setJobId(value.id);
      setRunId("");
      queryClient.setQueryData(["official-job", value.id], value);
      toast.success("Анализ топологии поставлен в очередь");
    },
    onError: (error) => toast.error(errorText(error)),
  });
  const startRun = useMutation({
    mutationFn: (profile: RoutingAlgorithmProfile) => createOfficialRun(importId, {
      algorithm_profile: profile,
    }),
    onSuccess: (value, profile) => {
      rememberRun(value, profile);
      queryClient.setQueryData(["official-run", value.id], value);
      if (value.job_id) {
        localStorage.setItem(JOB_KEY, value.job_id);
        setJobId(value.job_id);
      }
      toast.success(`${profileName(profile)} расчёт всех ОКС поставлен в очередь`);
    },
    onError: (error) => toast.error(errorText(error)),
  });
  const cancelJob = useMutation({
    mutationFn: () => cancelOfficialJob(jobId),
    onSuccess: (value) => {
      queryClient.setQueryData(["official-job", value.id], value);
      void run.refetch();
      toast.success("Отмена расчёта запрошена");
    },
    onError: (error) => toast.error(errorText(error)),
  });

  const currentImport = imported.data;
  const currentJob = job.data;
  const currentRun = run.data;
  useEffect(() => {
    if (!currentRun) return;
    const profile = runProfile(currentRun);
    if (profile === "expert_experimental") {
      localStorage.setItem(EXPERIMENTAL_RUN_KEY, currentRun.id);
    } else {
      localStorage.setItem(STABLE_RUN_KEY, currentRun.id);
    }
  }, [currentRun]);
  const activeRun = currentRun && currentJob?.run_id === currentRun.id ? currentRun : undefined;
  const currentRunProfile = currentRun ? runProfile(currentRun) : "stable";
  const isJobActive = currentJob?.state === "queued" || currentJob?.state === "running" || currentJob?.state === "cancel_requested";
  const isRunActive = currentRun?.state === "queued" || currentRun?.state === "running";
  const isRunLoading = Boolean(runId) && run.isPending && !run.isError;
  const isProcessing = upload.isPending || isRunLoading || Boolean(isRunActive);

  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (file) {
      setProcessingFilename(file.name);
      upload.mutate(file);
    }
    event.currentTarget.value = "";
  }

  if (isProcessing) {
    return (
      <div className="official-processing-screen" role="status" aria-live="polite">
        <div className="official-processing-card">
          <span className="official-processing-icon"><LoaderCircle className="is-spinning" size={24} /></span>
          <div>
            <span className="eyebrow">{upload.isPending ? "Подготовка данных" : "Расчёт маршрутов"}</span>
            <h1>{upload.isPending ? "Проверяем файл" : "Строим варианты подключения"}</h1>
            <p>{processingFilename || currentImport?.original_filename || "Набор данных"}</p>
          </div>
          {!upload.isPending && currentJob ? (
            <ProgressBar current={currentJob.progress_current} total={currentJob.progress_total} label={currentJob.phase} />
          ) : <div className="official-processing-line"><i /></div>}
          <small>Поиск сложного маршрута может занять несколько минут. Экран с результатами откроется автоматически.</small>
          {!upload.isPending && jobId && (job.isPending || isJobActive) && (
            <Button variant="outline" disabled={cancelJob.isPending} onClick={() => cancelJob.mutate()}>
              {cancelJob.isPending ? "Отменяем…" : "Отменить расчёт"}
            </Button>
          )}
        </div>
      </div>
    );
  }

  if (currentImport && currentRun?.state === "completed" && currentRun.result) {
    const exportReady = currentRun.result.variants.some((variant) =>
      variant.valid && variant.rank != null && variant.economics?.complete === true);
    const nextProfile: RoutingAlgorithmProfile = currentRunProfile === "stable"
      ? "expert_experimental"
      : "stable";
    return (
      <div className="official-map-workspace">
        <header className="map-workspace-toolbar">
          <div className="map-workspace-dataset">
            <span className="map-workspace-dataset__icon"><FileJson2 size={18} /></span>
            <div>
              <strong title={currentImport.original_filename}>{currentImport.original_filename}</strong>
              <small>{currentImport.report.feature_count.toLocaleString("ru-RU")} объектов · {humanFileSize(currentImport.input_size_bytes)}</small>
            </div>
          </div>
          <div className="map-workspace-status">
            <CheckCircle2 size={16} /> Данные проверены <span>·</span> {profileName(currentRunProfile)} расчёт <span>·</span> {currentRun.algorithm_version}
          </div>
          <div className="map-workspace-actions">
            {currentRunProfile !== "stable" && stableRunId && stableRunId !== currentRun.id && (
              <Button variant="outline" onClick={() => openRun(stableRunId)} title="Открыть последний основной расчёт">
                <Eye size={16} /> Основной
              </Button>
            )}
            {currentRunProfile !== "expert_experimental" && experimentalRunId && experimentalRunId !== currentRun.id && (
              <Button variant="outline" onClick={() => openRun(experimentalRunId)} title="Открыть последний эксперимент">
                <FlaskConical size={16} /> Эксперимент
              </Button>
            )}
            <Button
              variant="outline"
              disabled={startRun.isPending}
              title={nextProfile === "expert_experimental"
                ? "Запустить изолированный профиль с расширенным групповым поиском"
                : "Запустить основной алгоритм с неизменными настройками"}
              onClick={() => startRun.mutate(nextProfile)}
            >
              {startRun.isPending
                ? <LoaderCircle className="is-spinning" size={16} />
                : nextProfile === "expert_experimental" ? <FlaskConical size={16} /> : <Play size={16} />}
              {nextProfile === "expert_experimental" ? "Новый эксперимент" : "Новый основной"}
            </Button>
            <Button variant="outline" onClick={() => loadDemo.mutate()} disabled={loadDemo.isPending}>
              {loadDemo.isPending ? <LoaderCircle className="is-spinning" size={16} /> : <Eye size={16} />}
              {loadDemo.isPending ? "Открываем…" : "Последний расчёт"}
            </Button>
            <Button
              variant="outline"
              disabled={!exportReady}
              title={exportReady
                ? "Скачать официальный GeoJSON"
                : "Для экспорта нужны исходные данные реконструкции и итоговый rank"}
              onClick={() => { window.location.href = officialExportUrl(currentRun.id); }}
            >
              <Download size={16} />
              {exportReady ? "Скачать результат" : "Экспорт недоступен"}
            </Button>
            <input
              ref={inputRef}
              type="file"
              accept=".geojson,.json,application/geo+json,application/json"
              disabled={upload.isPending}
              onChange={handleFileChange}
            />
            <Button variant="outline" onClick={() => inputRef.current?.click()} disabled={upload.isPending}>
              {upload.isPending ? <LoaderCircle className="is-spinning" size={16} /> : <UploadCloud size={16} />}
              {upload.isPending ? "Проверяем…" : "Загрузить набор данных"}
            </Button>
          </div>
        </header>
        <RouteVisualization
          result={currentRun.result}
          runId={currentRun.id}
          importId={currentRun.import_id}
          warnings={currentImport.report.warnings}
        />
      </div>
    );
  }

  return (
    <div className="page official-page">
      <header className="page-heading">
        <div>
          <h1>Маршруты теплоснабжения</h1>
          <p>Загрузите GeoJSON, проверьте исходные данные и сравните варианты подключения объектов к теплосети.</p>
        </div>
      </header>

      <section className="official-hero">
        <Card className="official-upload-card">
          <div className="card-icon"><UploadCloud size={20} /></div>
          <div>
            <h2>Загрузить GeoJSON</h2>
            <p>Система проверит структуру файла и подготовит данные для расчёта. Максимальный размер — 3 ГБ.</p>
          </div>
          <input
            ref={inputRef}
            type="file"
            accept=".geojson,.json,application/geo+json,application/json"
            disabled={upload.isPending}
            onChange={handleFileChange}
          />
          <div className="official-upload-actions">
            <Button onClick={() => inputRef.current?.click()} disabled={upload.isPending}>
              {upload.isPending ? <LoaderCircle className="is-spinning" size={16} /> : <FileJson2 size={16} />}
              {upload.isPending ? "Проверяем…" : "Выбрать файл"}
            </Button>
            <Button variant="outline" onClick={() => loadDemo.mutate()} disabled={loadDemo.isPending}>
              {loadDemo.isPending ? <LoaderCircle className="is-spinning" size={16} /> : <Eye size={16} />}
              Открыть демо
            </Button>
          </div>
        </Card>
      </section>

      {!importId && !upload.isPending && (
        <Card><StateView state="empty" title="Загрузите набор данных" detail="После проверки здесь появятся состав файла, диагностика и запуск расчёта всех ОКС." /></Card>
      )}
      {imported.isPending && importId && <Card><StateView state="loading" title="Читаем импорт" /></Card>}
      {imported.isError && (
        <Card><StateView state="error" title="Импорт недоступен" detail={errorText(imported.error)} onRetry={() => void imported.refetch()} /></Card>
      )}

      {currentImport && (
        <div className="official-grid">
          <Card className="official-report-card">
            <header>
              <div><span className="eyebrow">Проверка данных</span><h2>{currentImport.original_filename}</h2></div>
              <StatusBadge value={currentImport.state} />
            </header>
            <dl className="official-metrics">
              <div><dt>Объектов</dt><dd>{currentImport.report.feature_count.toLocaleString("ru-RU")}</dd></div>
              <div><dt>Размер</dt><dd>{humanFileSize(currentImport.input_size_bytes)}</dd></div>
              <div><dt>Ошибок</dt><dd>{currentImport.report.errors.length}</dd></div>
              <div><dt>Предупреждений</dt><dd>{currentImport.report.warnings.length}</dd></div>
            </dl>
            <div className="official-types">
              {Object.entries(currentImport.report.feature_counts).map(([type, count]) => (
                <div key={type}><span>{type}</span><strong>{count}</strong></div>
              ))}
            </div>
            {currentImport.report.errors.length > 0 && (
              <div className="official-errors">
                {currentImport.report.errors.slice(0, 20).map((error, index) => (
                  <article key={`${error.code}-${error.feature_index}-${index}`}>
                    <XCircle size={16} /><div><strong>{error.code}</strong><p>{error.message}</p><small>feature #{error.feature_index}{error.feature_id ? ` · ${error.feature_id}` : ""}</small></div>
                  </article>
                ))}
              </div>
            )}
            {currentImport.report.warnings.length > 0 && (
              <div className="official-errors official-warnings">
                {currentImport.report.warnings.slice(0, 20).map((warning, index) => (
                  <article key={`${warning.code}-${warning.feature_index}-${index}`}>
                    <AlertTriangle size={16} /><div><strong>{warning.code}</strong><p>{warning.message}</p><small>feature #{warning.feature_index}{warning.feature_id ? ` · ${warning.feature_id}` : ""}</small></div>
                  </article>
                ))}
              </div>
            )}
            {currentImport.report.valid && (
              <div className="official-valid"><CheckCircle2 size={18} /><div><strong>Данные готовы к расчёту</strong><p>Структура файла проверена, можно анализировать сеть и точки подключения.</p></div></div>
            )}
          </Card>

          <Card className="official-job-card">
            <header><div><span className="eyebrow">Расчёт маршрутов</span><h2>Варианты подключения</h2></div>{currentJob && <StatusBadge value={currentJob.state} />}</header>
            {!currentJob ? (
              <>
                <p>Обработает все точки спроса, сравнит раздельные подключения и общие стволы, сохранит частичный результат для no-route.</p>
                <div className="card-actions">
                  <Button disabled={!currentImport.report.valid || startRun.isPending} onClick={() => startRun.mutate("stable")}>
                    {startRun.isPending ? <LoaderCircle className="is-spinning" size={16} /> : <Play size={16} />} Рассчитать варианты
                  </Button>
                  <Button
                    variant="outline"
                    disabled={!currentImport.report.valid || startRun.isPending}
                    title="Отдельный алгоритм с расширенным групповым поиском"
                    onClick={() => startRun.mutate("expert_experimental")}
                  >
                    {startRun.isPending ? <LoaderCircle className="is-spinning" size={16} /> : <FlaskConical size={16} />}
                    Экспериментальный алгоритм
                  </Button>
                  <Button variant="outline" disabled={!currentImport.report.valid || startJob.isPending} onClick={() => startJob.mutate()}>
                    Только топология
                  </Button>
                </div>
              </>
            ) : (
              <>
                <ProgressBar current={currentJob.progress_current} total={currentJob.progress_total} label={currentJob.phase} />
                <dl className="provenance-list">
                  <div><dt>Job ID</dt><dd>{currentJob.id}</dd></div>
                  <div><dt>Попытка</dt><dd>{currentJob.attempt}</dd></div>
                  <div><dt>Тип</dt><dd>{currentJob.job_type}</dd></div>
                  {activeRun && <div><dt>Run ID</dt><dd>{activeRun.id}</dd></div>}
                  {activeRun && <div><dt>Алгоритм</dt><dd>{activeRun.algorithm_version}</dd></div>}
                </dl>
                {currentJob.error_message && <p className="text-danger">{currentJob.error_code}: {currentJob.error_message}</p>}
                {currentJob.result !== undefined && (
                  <details className="official-result-details">
                    <summary>Технический JSON результата</summary>
                    <pre className="official-result">{JSON.stringify(currentJob.result, null, 2)}</pre>
                  </details>
                )}
                <div className="card-actions">
                  <Button variant="outline" onClick={() => void job.refetch()}><RotateCcw size={15} /> Обновить</Button>
                  {isJobActive && <Button variant="outline" disabled={cancelJob.isPending} onClick={() => cancelJob.mutate()}>Отменить</Button>}
                  {!isJobActive && currentJob.job_type === "topology_analysis" && (
                    <>
                      <Button disabled={startRun.isPending} onClick={() => startRun.mutate("stable")}>
                        {startRun.isPending ? <LoaderCircle className="is-spinning" size={15} /> : <Play size={15} />} Рассчитать варианты
                      </Button>
                      <Button variant="outline" disabled={startRun.isPending} onClick={() => startRun.mutate("expert_experimental")}>
                        <FlaskConical size={15} /> Экспериментальный алгоритм
                      </Button>
                    </>
                  )}
                  {!isJobActive && currentJob.job_type !== "topology_analysis" && (
                    <>
                      <Button variant="outline" disabled={startRun.isPending} onClick={() => startRun.mutate("stable")}>
                        <Play size={15} /> Новый основной
                      </Button>
                      <Button variant="outline" disabled={startRun.isPending} onClick={() => startRun.mutate("expert_experimental")}>
                        <FlaskConical size={15} /> Новый эксперимент
                      </Button>
                    </>
                  )}
                </div>
              </>
            )}
          </Card>
        </div>
      )}
    </div>
  );
}
