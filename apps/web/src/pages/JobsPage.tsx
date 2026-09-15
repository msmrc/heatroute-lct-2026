import { useQuery } from "@tanstack/react-query";
import { BriefcaseBusiness, ExternalLink, RotateCcw } from "lucide-react";
import { Link, useSearchParams } from "react-router-dom";

import { Button } from "../components/ui/button";
import { Card, ProgressBar, StateView, StatusBadge } from "../components/ui/primitives";
import { listJobs, listProjects } from "../shared/api";
import { errorText, formatDate, shortId } from "../shared/format";

export function JobsPage() {
  const [params, setParams] = useSearchParams();
  const projectId = params.get("project") ?? undefined;
  const projects = useQuery({ queryKey: ["projects"], queryFn: ({ signal }) => listProjects(signal) });
  const jobs = useQuery({
    queryKey: ["jobs", projectId],
    queryFn: ({ signal }) => listJobs(projectId, signal),
    refetchInterval: (query) => query.state.data?.some((item) => ["queued", "running", "cancel_requested"].includes(item.state)) ? 1200 : false,
  });
  return (
    <div className="page jobs-page">
      <header className="page-heading"><div><span className="eyebrow">Очередь и история</span><h1>Задачи</h1><p>Импорты и расчёты с серверным состоянием, этапом и попыткой выполнения.</p></div><Button variant="outline" onClick={() => void jobs.refetch()}><RotateCcw size={15} /> Обновить</Button></header>
      <div className="page-toolbar">
        <select className="select compact-select" value={projectId ?? ""} onChange={(event) => setParams(event.target.value ? { project: event.target.value } : {})}>
          <option value="">Все проекты</option>
          {(projects.data ?? []).slice().reverse().slice(0, 60).map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}
        </select>
        <span>{jobs.data?.length ?? 0} задач</span>
      </div>
      <Card className="table-card jobs-table">
        <div className="simple-table">
          <div className="simple-table__head"><span>Задача</span><span>Тип / этап</span><span>Прогресс</span><span>Попытка</span><span>Создана</span><span /></div>
          {jobs.isPending && <StateView compact state="loading" title="Читаем очередь" />}
          {jobs.isError && <StateView compact state="error" title="Задачи недоступны" detail={errorText(jobs.error)} onRetry={() => void jobs.refetch()} />}
          {(jobs.data ?? []).map((job) => <div className="simple-table__row" key={job.id}><span><span className="job-kind-icon"><BriefcaseBusiness size={15} /></span><span><strong>{shortId(job.id)}</strong><StatusBadge value={job.state} /></span></span><span><strong>{job.kind}</strong><small>{job.phase?.replaceAll("_", " ") ?? "Ожидание"}</small></span><span><ProgressBar current={job.progress_current} total={job.progress_total} label={job.progress_unit ?? "Задача"} /></span><span>{job.attempt} / {job.retryable ? "retry" : "final"}</span><span>{formatDate(job.created_at)}</span><span>{job.resource_id && job.project_id && job.resource_type === "run" ? <Link className="icon-button" to={`/projects/${job.project_id}/runs/${job.resource_id}`} aria-label="Открыть run"><ExternalLink size={15} /></Link> : job.resource_id && job.project_id ? <Link className="icon-button" to={`/projects/${job.project_id}/imports/${job.resource_id}`} aria-label="Открыть импорт"><ExternalLink size={15} /></Link> : null}</span></div>)}
          {!jobs.isPending && jobs.data?.length === 0 && <StateView compact state="empty" title="Задач нет" detail="Запустите импорт или расчёт маршрута." />}
        </div>
      </Card>
    </div>
  );
}
