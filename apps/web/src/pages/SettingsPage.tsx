import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Save, ShieldCheck } from "lucide-react";
import type { FormEvent } from "react";
import { useParams } from "react-router-dom";
import { toast } from "sonner";

import { Button } from "../components/ui/button";
import { Card, Field, Input, StateView } from "../components/ui/primitives";
import { getProject, updateProject } from "../shared/api";
import { errorText, formText } from "../shared/format";

export function SettingsPage() {
  const { projectId = "" } = useParams();
  const queryClient = useQueryClient();
  const project = useQuery({ queryKey: ["project", projectId], queryFn: ({ signal }) => getProject(projectId, signal) });
  const save = useMutation({
    mutationFn: async (form: HTMLFormElement) => {
      if (!project.data) throw new Error("Проект не загружен");
      const data = new FormData(form);
      return updateProject(project.data, {
        name: formText(data, "name"),
        description: formText(data, "description") || null,
        working_crs: formText(data, "working_crs") || null,
        crs_confirmed: data.get("crs_confirmed") === "on",
      });
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["project", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["projects"] }),
      ]);
      toast.success("Настройки сохранены новой revision проекта");
    },
    onError: (error) => toast.error((error as { status?: number }).status === 412 ? "Настройки устарели — обновите страницу" : "Не удалось сохранить", { description: errorText(error) }),
  });
  if (project.isPending) return <StateView state="loading" title="Загружаем настройки" />;
  if (project.isError) return <StateView state="error" title="Настройки недоступны" detail={errorText(project.error)} onRetry={() => void project.refetch()} />;
  const submit = (event: FormEvent<HTMLFormElement>) => { event.preventDefault(); save.mutate(event.currentTarget); };
  return (
    <div className="page settings-page">
      <header className="page-heading"><div><span className="eyebrow">Project revision {project.data.current_revision}</span><h1>Настройки проекта</h1><p>Изменения защищены If-Match: конфликт версий не перезапишет чужую правку.</p></div></header>
      <Card className="settings-card">
        <form onSubmit={submit}>
          <div className="form-grid">
            <Field label="Название"><Input name="name" defaultValue={project.data.name} required /></Field>
            <Field label="Рабочая CRS" hint="Изменение CRS требует повторного подтверждения"><Input name="working_crs" defaultValue={project.data.working_crs ?? ""} placeholder="EPSG:32637" /></Field>
          </div>
          <Field label="Описание"><textarea className="textarea" name="description" defaultValue={project.data.description ?? ""} /></Field>
          <label className="confirm-row"><input type="checkbox" name="crs_confirmed" defaultChecked={project.data.crs_confirmed} /><span><strong>Рабочая CRS и порядок осей подтверждены</strong><small>Без подтверждения preflight может блокировать расчёт.</small></span><ShieldCheck size={17} /></label>
          <div className="card-actions"><Button type="submit" disabled={save.isPending}><Save size={15} /> {save.isPending ? "Сохраняем…" : "Сохранить"}</Button></div>
        </form>
      </Card>
    </div>
  );
}
