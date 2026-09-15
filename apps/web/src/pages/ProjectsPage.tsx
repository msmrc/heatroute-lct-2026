import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, FolderPlus, MapPinned, Search, Sparkles } from "lucide-react";
import { useMemo, useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";

import { Button } from "../components/ui/button";
import { Card, Dialog, Field, Input, StateView, StatusBadge } from "../components/ui/primitives";
import { createProject, createScenario, createScenarioRevision, listJobs, listProjects } from "../shared/api";
import { errorText, formatDate, formText } from "../shared/format";
import { defaultScenarioDraft } from "../shared/scenario";

export function ProjectsPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [search, setSearch] = useState("");
  const [dialogOpen, setDialogOpen] = useState(false);
  const projects = useQuery({ queryKey: ["projects"], queryFn: ({ signal }) => listProjects(signal) });
  const jobs = useQuery({ queryKey: ["jobs"], queryFn: ({ signal }) => listJobs(undefined, signal) });

  const create = useMutation({
    mutationFn: async (payload: { name: string; description: string; sourceMode: "synthetic" | "mixed" | "provided" }) =>
      createProject({
        name: payload.name,
        description: payload.description || null,
        working_crs: "EPSG:32637",
        crs_confirmed: true,
        source_mode: payload.sourceMode,
      }),
    onSuccess: async (project) => {
      await queryClient.invalidateQueries({ queryKey: ["projects"] });
      setDialogOpen(false);
      void navigate(`/projects/${project.id}/workspace`);
    },
    onError: (error) => toast.error("Не удалось создать проект", { description: errorText(error) }),
  });
  const openDemo = useMutation({
    mutationFn: async () => {
      const project = await createProject({
        name: "Демо: новый тепловой ввод",
        description: "Синтетический район для сквозной демонстрации HeatRoute",
        working_crs: "EPSG:32637",
        crs_confirmed: true,
        source_mode: "synthetic",
      });
      const scenario = await createScenario(project.id, "Обход запретной зоны");
      const revision = await createScenarioRevision(scenario.id, 0, defaultScenarioDraft(true));
      return { project, scenario, revision };
    },
    onSuccess: async ({ project, scenario }) => {
      await queryClient.invalidateQueries({ queryKey: ["projects"] });
      toast.success("Демонстрационный проект готов");
      void navigate(`/projects/${project.id}/workspace?scenario=${scenario.id}`);
    },
    onError: (error) => toast.error("Не удалось подготовить демо", { description: errorText(error) }),
  });

  const visibleProjects = useMemo(() => {
    const query = search.trim().toLocaleLowerCase("ru");
    return (projects.data ?? [])
      .filter((project) => !query || `${project.name} ${project.description ?? ""}`.toLocaleLowerCase("ru").includes(query))
      .slice()
      .sort((a, b) => b.updated_at.localeCompare(a.updated_at))
      .slice(0, 60);
  }, [projects.data, search]);

  function submitCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    create.mutate({
      name: formText(form, "name").trim(),
      description: formText(form, "description").trim(),
      sourceMode: formText(form, "source_mode", "provided") as "synthetic" | "mixed" | "provided",
    });
  }

  return (
    <div className="page projects-page">
      <header className="page-heading">
        <div><span className="eyebrow">HeatRoute workspace</span><h1>Проекты</h1><p>Исходные данные, сценарии и расчёты хранятся с точными версиями.</p></div>
        <div className="heading-actions">
          <Button variant="outline" onClick={() => setDialogOpen(true)}><FolderPlus size={16} /> Новый проект</Button>
          <Button onClick={() => openDemo.mutate()} disabled={openDemo.isPending}>
            <Sparkles size={16} /> {openDemo.isPending ? "Готовим демо…" : "Открыть демо"}
          </Button>
        </div>
      </header>

      <div className="page-toolbar">
        <label className="search-box"><Search size={16} /><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Найти проект" /></label>
        <span>{visibleProjects.length} из {projects.data?.length ?? 0}</span>
      </div>

      {projects.isPending && <StateView state="loading" title="Загружаем проекты" />}
      {projects.isError && <StateView state="error" title="Проекты недоступны" detail={errorText(projects.error)} onRetry={() => void projects.refetch()} />}
      {!projects.isPending && !projects.isError && visibleProjects.length === 0 && (
        <StateView state="empty" title={search ? "Ничего не найдено" : "Проектов пока нет"} detail={search ? "Измените поисковый запрос." : "Создайте проект или откройте готовый синтетический сценарий."} />
      )}
      <div className="project-grid">
        {visibleProjects.map((project) => {
          const lastJob = jobs.data?.find((job) => job.project_id === project.id);
          const blockers = project.crs_confirmed ? 0 : 1;
          return (
            <Card key={project.id} className="project-card" onClick={() => void navigate(`/projects/${project.id}/workspace`)}>
              <div className="project-card__top">
                <span className="project-icon"><MapPinned size={19} /></span>
                <StatusBadge value={project.source_mode} />
              </div>
              <div className="project-card__body">
                <h2>{project.name}</h2>
                <p>{project.description || "Описание не добавлено"}</p>
              </div>
              <dl className="project-card__stats">
                <div><dt>Версия</dt><dd>rev {project.current_revision}</dd></div>
                <div><dt>Последняя задача</dt><dd>{lastJob ? <StatusBadge value={lastJob.state} /> : "Нет"}</dd></div>
                <div><dt>Блокеры</dt><dd className={blockers ? "text-danger" : ""}>{blockers}</dd></div>
              </dl>
              <footer><span>Изменён {formatDate(project.updated_at)}</span><ArrowRight size={16} /></footer>
            </Card>
          );
        })}
      </div>

      <Dialog open={dialogOpen} onClose={() => setDialogOpen(false)} title="Новый проект" description="Рабочую CRS можно изменить позже с обязательным повторным подтверждением.">
        <form className="dialog-form" onSubmit={submitCreate}>
          <Field label="Название"><Input name="name" required minLength={2} autoFocus placeholder="Например, квартал Северный" /></Field>
          <Field label="Описание"><textarea name="description" className="textarea" placeholder="Цель планирования и границы проекта" /></Field>
          <Field label="Тип исходных данных">
            <select name="source_mode" className="select" defaultValue="provided">
              <option value="provided">Поставка заказчика</option>
              <option value="mixed">Смешанные источники</option>
              <option value="synthetic">Синтетические данные</option>
            </select>
          </Field>
          <div className="dialog-actions"><Button variant="ghost" type="button" onClick={() => setDialogOpen(false)}>Отмена</Button><Button type="submit" disabled={create.isPending}>{create.isPending ? "Создаём…" : "Создать проект"}</Button></div>
        </form>
      </Dialog>
    </div>
  );
}
