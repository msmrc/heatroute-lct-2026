import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, FileJson2, LoaderCircle, Play, RotateCcw, UploadCloud, XCircle } from "lucide-react";
import { useRef, useState } from "react";
import { toast } from "sonner";

import { Badge, Card, ProgressBar, StateView, StatusBadge } from "../components/ui/primitives";
import { Button } from "../components/ui/button";
import {
  ApiError,
  cancelOfficialJob,
  createOfficialImport,
  createTopologyJob,
  getOfficialImport,
  getOfficialJob,
  humanFileSize,
  type OfficialImport,
} from "../shared/api";

const IMPORT_KEY = "heatroute.officialImportId";
const JOB_KEY = "heatroute.officialJobId";

function errorText(error: unknown): string {
  return error instanceof ApiError ? error.message : error instanceof Error ? error.message : "Неизвестная ошибка";
}

export function OfficialWorkspacePage() {
  const queryClient = useQueryClient();
  const inputRef = useRef<HTMLInputElement>(null);
  const [importId, setImportId] = useState(() => localStorage.getItem(IMPORT_KEY) ?? "");
  const [jobId, setJobId] = useState(() => localStorage.getItem(JOB_KEY) ?? "");

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

  const upload = useMutation({
    mutationFn: createOfficialImport,
    onSuccess: (value: OfficialImport) => {
      localStorage.setItem(IMPORT_KEY, value.id);
      localStorage.removeItem(JOB_KEY);
      setImportId(value.id);
      setJobId("");
      queryClient.setQueryData(["official-import", value.id], value);
      toast.success("GeoJSON проверен и сохранён");
    },
    onError: (error) => toast.error(errorText(error)),
  });
  const startJob = useMutation({
    mutationFn: () => createTopologyJob(importId),
    onSuccess: (value) => {
      localStorage.setItem(JOB_KEY, value.id);
      setJobId(value.id);
      queryClient.setQueryData(["official-job", value.id], value);
      toast.success("Анализ топологии поставлен в очередь");
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
  const isJobActive = currentJob?.state === "queued" || currentJob?.state === "running" || currentJob?.state === "cancel_requested";

  return (
    <div className="page official-page">
      <header className="page-heading">
        <div>
          <span className="eyebrow">Официальный контур · Java</span>
          <h1>Проверка исходных данных</h1>
          <p>Один GeoJSON, семь обязательных типов, потоковая проверка и сохраняемая задача анализа топологии.</p>
        </div>
        <a className="button-link button-link--outline" href="/swagger-ui.html" target="_blank" rel="noreferrer">Swagger</a>
      </header>

      <section className="official-hero">
        <Card className="official-upload-card">
          <div className="card-icon"><UploadCloud size={20} /></div>
          <div>
            <h2>Загрузить официальный GeoJSON</h2>
            <p>Файл проверяется Java-сервисом и сохраняется в PostGIS. Лимит по ТЗ — до 3 ГБ.</p>
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
          <Button onClick={() => inputRef.current?.click()} disabled={upload.isPending}>
            {upload.isPending ? <LoaderCircle className="is-spinning" size={16} /> : <FileJson2 size={16} />}
            {upload.isPending ? "Проверяем…" : "Выбрать файл"}
          </Button>
        </Card>

        <Card className="official-status-card">
          <span>Контур исполнения</span>
          <strong>Java 11</strong>
          <p>Spring Boot 2.6.3 · PostgreSQL/PostGIS · Liquibase</p>
          <Badge tone="success">Python удалён из runtime</Badge>
        </Card>
      </section>

      {!importId && !upload.isPending && (
        <Card><StateView state="empty" title="Загрузите набор данных" detail="После проверки здесь появятся состав файла, ошибки контракта и запуск анализа топологии." /></Card>
      )}
      {imported.isPending && importId && <Card><StateView state="loading" title="Читаем импорт" /></Card>}
      {imported.isError && (
        <Card><StateView state="error" title="Импорт недоступен" detail={errorText(imported.error)} onRetry={() => void imported.refetch()} /></Card>
      )}

      {currentImport && (
        <div className="official-grid">
          <Card className="official-report-card">
            <header>
              <div><span className="eyebrow">Результат импорта</span><h2>{currentImport.original_filename}</h2></div>
              <StatusBadge value={currentImport.state} />
            </header>
            <dl className="official-metrics">
              <div><dt>Объектов</dt><dd>{currentImport.report.feature_count.toLocaleString("ru-RU")}</dd></div>
              <div><dt>Размер</dt><dd>{humanFileSize(currentImport.input_size_bytes)}</dd></div>
              <div><dt>Контракт</dt><dd>{currentImport.report.contract_version}</dd></div>
              <div><dt>Ошибок</dt><dd>{currentImport.report.errors.length}</dd></div>
              <div><dt>Профиль</dt><dd>{currentImport.report.input_profile === "strict_official" ? "Строгий" : "Датасет"}</dd></div>
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
              <div className="official-valid"><CheckCircle2 size={18} /><div><strong>Входной контракт пройден</strong><p>{currentImport.report.input_profile === "strict_official" ? "Набор готов к полному анализу существующей сети и кандидатов врезки." : "Набор принят в compatibility-профиле; ограничения расчёта перечислены в предупреждениях."}</p></div></div>
            )}
          </Card>

          <Card className="official-job-card">
            <header><div><span className="eyebrow">Следующий этап</span><h2>Анализ топологии</h2></div>{currentJob && <StatusBadge value={currentJob.state} />}</header>
            {!currentJob ? (
              <>
                <p>Проверит направление к источнику, циклы, оборванные ссылки и сформирует детерминированные кандидаты врезки.</p>
                <Button disabled={!currentImport.report.valid || startJob.isPending} onClick={() => startJob.mutate()}>
                  {startJob.isPending ? <LoaderCircle className="is-spinning" size={16} /> : <Play size={16} />} Запустить
                </Button>
              </>
            ) : (
              <>
                <ProgressBar current={currentJob.progress_current} total={currentJob.progress_total} label={currentJob.phase} />
                <dl className="provenance-list">
                  <div><dt>Job ID</dt><dd>{currentJob.id}</dd></div>
                  <div><dt>Попытка</dt><dd>{currentJob.attempt}</dd></div>
                  <div><dt>Тип</dt><dd>{currentJob.job_type}</dd></div>
                </dl>
                {currentJob.error_message && <p className="text-danger">{currentJob.error_code}: {currentJob.error_message}</p>}
                {currentJob.result !== undefined && <pre className="official-result">{JSON.stringify(currentJob.result, null, 2)}</pre>}
                <div className="card-actions">
                  <Button variant="outline" onClick={() => void job.refetch()}><RotateCcw size={15} /> Обновить</Button>
                  {isJobActive && <Button variant="outline" disabled={cancelJob.isPending} onClick={() => cancelJob.mutate()}>Отменить</Button>}
                </div>
              </>
            )}
          </Card>
        </div>
      )}
    </div>
  );
}
