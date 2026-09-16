import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, Eye, FileJson2, LoaderCircle, Play, RotateCcw, UploadCloud, XCircle } from "lucide-react";
import { useRef, useState } from "react";
import { toast } from "sonner";

import { Card, ProgressBar, StateView, StatusBadge } from "../components/ui/primitives";
import { Button } from "../components/ui/button";
import { RouteVisualization } from "../components/official/RouteVisualization";
import {
  ApiError,
  cancelOfficialJob,
  createOfficialImport,
  createOfficialRun,
  createTopologyJob,
  getOfficialImport,
  getOfficialJob,
  getLatestOfficialRun,
  getOfficialRun,
  humanFileSize,
  type OfficialImport,
} from "../shared/api";

const IMPORT_KEY = "heatroute.officialImportId";
const JOB_KEY = "heatroute.officialJobId";
const RUN_KEY = "heatroute.officialRunId";

function errorText(error: unknown): string {
  return error instanceof ApiError ? error.message : error instanceof Error ? error.message : "Неизвестная ошибка";
}

export function OfficialWorkspacePage() {
  const queryClient = useQueryClient();
  const inputRef = useRef<HTMLInputElement>(null);
  const [importId, setImportId] = useState(() => localStorage.getItem(IMPORT_KEY) ?? "");
  const [jobId, setJobId] = useState(() => localStorage.getItem(JOB_KEY) ?? "");
  const [runId, setRunId] = useState(() => localStorage.getItem(RUN_KEY) ?? "");

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
    mutationFn: createOfficialImport,
    onSuccess: (value: OfficialImport) => {
      localStorage.setItem(IMPORT_KEY, value.id);
      localStorage.removeItem(JOB_KEY);
      localStorage.removeItem(RUN_KEY);
      setImportId(value.id);
      setJobId("");
      setRunId("");
      queryClient.setQueryData(["official-import", value.id], value);
      toast.success("GeoJSON проверен и сохранён");
    },
    onError: (error) => toast.error(errorText(error)),
  });
  const loadDemo = useMutation({
    mutationFn: () => getLatestOfficialRun(),
    onSuccess: (value) => {
      localStorage.setItem(IMPORT_KEY, value.import_id);
      localStorage.setItem(RUN_KEY, value.id);
      setImportId(value.import_id);
      setRunId(value.id);
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
    mutationFn: () => createOfficialRun(importId),
    onSuccess: (value) => {
      localStorage.setItem(RUN_KEY, value.id);
      setRunId(value.id);
      queryClient.setQueryData(["official-run", value.id], value);
      if (value.job_id) {
        localStorage.setItem(JOB_KEY, value.job_id);
        setJobId(value.job_id);
      }
      toast.success("Расчёт всех ОКС поставлен в очередь");
    },
    onError: (error) => toast.error(errorText(error)),
  });
  const cancelJob = useMutation({
    mutationFn: () => cancelOfficialJob(jobId),
    onSuccess: (value) => queryClient.setQueryData(["official-job", value.id], value),
    onError: (error) => toast.error(errorText(error)),
  });

  const currentImport = imported.data;
  const currentJob = job.data;
  const currentRun = run.data;
  const activeRun = currentRun && currentJob?.run_id === currentRun.id ? currentRun : undefined;
  const isJobActive = currentJob?.state === "queued" || currentJob?.state === "running" || currentJob?.state === "cancel_requested";

  if (currentImport && currentRun?.state === "completed" && currentRun.result) {
    return (
      <div className="official-map-workspace">
        <header className="map-workspace-toolbar">
          <div className="map-workspace-dataset">
            <span className="map-workspace-dataset__icon"><FileJson2 size={18} /></span>
            <div>
              <strong>Официальный GeoJSON</strong>
              <small>{currentImport.report.feature_count.toLocaleString("ru-RU")} объектов · {humanFileSize(currentImport.input_size_bytes)}</small>
            </div>
          </div>
          <div className="map-workspace-status"><CheckCircle2 size={16} /> Данные проверены <span>·</span> Расчёт завершён</div>
          <div className="map-workspace-actions">
            <input
              ref={inputRef}
              type="file"
              accept=".geojson,.json,application/geo+json,application/json"
              disabled={upload.isPending}
              onChange={(event) => {
                const file = event.target.files?.[0];
                if (file) upload.mutate(file);
                event.currentTarget.value = "";
              }}
            />
            <Button variant="outline" onClick={() => inputRef.current?.click()} disabled={upload.isPending}>
              {upload.isPending ? <LoaderCircle className="is-spinning" size={16} /> : <UploadCloud size={16} />}
              {upload.isPending ? "Проверяем…" : "Загрузить набор данных"}
            </Button>
          </div>
        </header>
        <RouteVisualization
          result={currentRun.result}
          importId={currentRun.import_id}
          warningCount={currentImport.report.warnings.length}
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
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) upload.mutate(file);
              event.currentTarget.value = "";
            }}
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
              <div className="official-valid"><CheckCircle2 size={18} /><div><strong>Данные готовы к расчёту</strong><p>{currentImport.report.input_profile === "strict_official" ? "Структура файла проверена, можно анализировать сеть и точки подключения." : "Файл принят с допустимыми отклонениями. Перед использованием результата ознакомьтесь с предупреждениями."}</p></div></div>
            )}
          </Card>

          <Card className="official-job-card">
            <header><div><span className="eyebrow">Расчёт маршрутов</span><h2>Варианты подключения</h2></div>{currentJob && <StatusBadge value={currentJob.state} />}</header>
            {!currentJob ? (
              <>
                <p>Обработает все точки спроса, сравнит раздельные подключения и общие стволы, сохранит частичный результат для no-route.</p>
                <div className="card-actions">
                  <Button disabled={!currentImport.report.valid || startRun.isPending} onClick={() => startRun.mutate()}>
                    {startRun.isPending ? <LoaderCircle className="is-spinning" size={16} /> : <Play size={16} />} Рассчитать варианты
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
                    <Button disabled={startRun.isPending} onClick={() => startRun.mutate()}>
                      {startRun.isPending ? <LoaderCircle className="is-spinning" size={15} /> : <Play size={15} />} Рассчитать варианты
                    </Button>
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
