import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, Database, FileUp, Layers3, Search, ShieldAlert } from "lucide-react";
import { useMemo, useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { toast } from "sonner";

import { Button } from "../components/ui/button";
import { Card, Dialog, Field, Input, StateView, StatusBadge } from "../components/ui/primitives";
import {
  getDatasetDiff,
  listDatasetLayers,
  listDatasetVersions,
  listFeatures,
  listImports,
  uploadDataset,
} from "../shared/api";
import { errorText, formatDate, formatNumber, formText, shortId } from "../shared/format";

export function DataPage() {
  const { projectId = "" } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [uploadOpen, setUploadOpen] = useState(false);
  const [search, setSearch] = useState("");
  const [selectedVersionId, setSelectedVersionId] = useState<string>();
  const versions = useQuery({
    queryKey: ["dataset-versions", projectId],
    queryFn: ({ signal }) => listDatasetVersions(projectId, signal),
  });
  const imports = useQuery({
    queryKey: ["imports", projectId],
    queryFn: ({ signal }) => listImports(projectId, signal),
    refetchInterval: (query) => query.state.data?.some((item) => ["queued", "running"].includes(item.job_state)) ? 1200 : false,
  });
  const effectiveVersionId = selectedVersionId ?? versions.data?.[0]?.id;
  const selectedVersion = versions.data?.find((item) => item.id === effectiveVersionId);
  const hasPublishedBaseline = Boolean(selectedVersion && (versions.data ?? []).some(
    (item) => item.status === "published" && item.version < selectedVersion.version,
  ));
  const layers = useQuery({
    queryKey: ["dataset-layers", projectId, effectiveVersionId],
    queryFn: ({ signal }) => listDatasetLayers(projectId, effectiveVersionId!, signal),
    enabled: Boolean(effectiveVersionId),
  });
  const diff = useQuery({
    queryKey: ["dataset-diff", projectId, effectiveVersionId],
    queryFn: ({ signal }) => getDatasetDiff(projectId, effectiveVersionId!, signal),
    enabled: selectedVersion?.status === "published" && hasPublishedBaseline,
  });
  const features = useQuery({
    queryKey: ["features", projectId, effectiveVersionId],
    queryFn: ({ signal }) => listFeatures(projectId, effectiveVersionId!, signal),
    enabled: selectedVersion?.status === "published",
  });

  const upload = useMutation({
    mutationFn: async (form: HTMLFormElement) => {
      const data = new FormData(form);
      const file = data.get("file");
      if (!(file instanceof File) || file.size === 0) throw new Error("Выберите GeoJSON, GeoPackage или CSV");
      return uploadDataset(projectId, file, {
        datasetName: formText(data, "dataset_name", "Новая поставка"),
        purpose: formText(data, "purpose", "planning"),
        licenseNote: formText(data, "license_note"),
      });
    },
    onSuccess: async (accepted) => {
      setUploadOpen(false);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["dataset-versions", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["imports", projectId] }),
      ]);
      void navigate(`/projects/${projectId}/imports/${accepted.import_id}`);
    },
    onError: (error) => toast.error("Загрузка не началась", { description: errorText(error) }),
  });

  const filteredFeatures = useMemo(() => {
    const query = search.trim().toLowerCase();
    return (features.data ?? []).filter((item) => !query || `${item.source_id} ${item.kind} ${item.source_layer}`.toLowerCase().includes(query));
  }, [features.data, search]);

  const submitUpload = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    upload.mutate(event.currentTarget);
  };

  return (
    <div className="page data-page">
      <header className="page-heading">
        <div><span className="eyebrow">Управление данными</span><h1>Поставки и версии</h1><p>Raw-артефакты неизменны; mapping, validation и публикация оставляют след происхождения.</p></div>
        <div className="heading-actions"><Link className="button-link button-link--outline" to={`/projects/${projectId}/quality`}><ShieldAlert size={15} /> Качество</Link><Button onClick={() => setUploadOpen(true)}><FileUp size={15} /> Импортировать</Button></div>
      </header>

      <div className="data-layout">
        <aside className="version-sidebar">
          <h2>Версии</h2>
          {versions.isPending && <StateView compact state="loading" title="Загрузка" />}
          {versions.isError && <StateView compact state="error" title="Версии недоступны" onRetry={() => void versions.refetch()} />}
          {(versions.data ?? []).map((version) => (
            <button key={version.id} type="button" className={version.id === effectiveVersionId ? "version-item is-active" : "version-item"} onClick={() => setSelectedVersionId(version.id)}>
              <span><Database size={15} /><strong>{version.source_name}</strong></span>
              <span><small>v{version.version} · {shortId(version.id)}</small><StatusBadge value={version.status} /></span>
            </button>
          ))}
          {!versions.isPending && versions.data?.length === 0 && <StateView compact state="empty" title="Версий нет" detail="Загрузите первую поставку." />}
          <h2 className="sidebar-subtitle">Импорты</h2>
          {(imports.data ?? []).slice(0, 8).map((item) => (
            <button key={item.id} type="button" className="import-item" onClick={() => void navigate(`/projects/${projectId}/imports/${item.id}`)}>
              <span><strong>{shortId(item.id)}</strong><small>{formatDate(item.created_at)}</small></span><StatusBadge value={item.state} />
            </button>
          ))}
        </aside>

        <section className="data-main">
          {!selectedVersion ? (
            <StateView state="empty" title="Нет выбранной версии" detail="Начните импорт, чтобы проверить и опубликовать исходные данные." />
          ) : (
            <>
              <Card className="dataset-hero">
                <div><span className="dataset-icon"><Layers3 size={20} /></span><div><h2>{selectedVersion.source_name}</h2><p>Dataset version {selectedVersion.version} · создана {formatDate(selectedVersion.created_at)}</p></div></div>
                <StatusBadge value={selectedVersion.status} />
                <dl>
                  <div><dt>Рабочая CRS</dt><dd>{selectedVersion.working_crs ?? "Не подтверждена"}</dd></div>
                  <div><dt>Raw hashes</dt><dd>{selectedVersion.raw_hashes.length}</dd></div>
                  <div><dt>Источник</dt><dd>{selectedVersion.source_type}</dd></div>
                  <div><dt>Опубликована</dt><dd>{formatDate(selectedVersion.published_at)}</dd></div>
                </dl>
              </Card>
              <div className="metric-cards">
                <Card><span>Добавлено</span><strong>{diff.data?.added.length ?? "—"}</strong></Card>
                <Card><span>Изменено</span><strong>{diff.data?.changed.length ?? "—"}</strong></Card>
                <Card><span>Удалено</span><strong>{diff.data?.deleted.length ?? "—"}</strong></Card>
                <Card><span>Без изменений</span><strong>{formatNumber(diff.data?.unchanged)}</strong></Card>
              </div>
              <Card className="table-card">
                <header><div><h2>Слои</h2><p>Сопоставление и фактическое число объектов</p></div></header>
                <div className="simple-table">
                  <div className="simple-table__head"><span>Слой</span><span>Геометрия</span><span>Canonical kind</span><span>Объекты</span><span>Статус</span></div>
                  {(layers.data ?? []).map((layer) => <div className="simple-table__row" key={layer.id}><span><strong>{layer.name}</strong><small>{layer.source_crs ?? "CRS неизвестна"}</small></span><span>{layer.geometry_type ?? "—"}</span><span>{layer.mapped_kind ?? "Не сопоставлен"}</span><span>{formatNumber(layer.feature_count)}</span><StatusBadge value={layer.status} /></div>)}
                  {layers.isPending && <StateView compact state="loading" title="Читаем слои" />}
                </div>
              </Card>
              <Card className="table-card">
                <header><div><h2>Опубликованные объекты</h2><p>Каждая строка хранит source layer, source ID и quality flags</p></div><label className="search-box search-box--small"><Search size={14} /><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="ID, тип или слой" /></label></header>
                {selectedVersion.status !== "published" ? <StateView compact state="empty" title="Объекты ещё не опубликованы" detail="Завершите validation и публикацию в мастере импорта." /> : features.isError ? <StateView compact state="error" title="Объекты недоступны" detail={errorText(features.error)} onRetry={() => void features.refetch()} /> : (
                  <div className="simple-table feature-table">
                    <div className="simple-table__head"><span>Source ID</span><span>Тип</span><span>Слой</span><span>Качество</span><span>Источник</span></div>
                    {filteredFeatures.map((feature) => <div className="simple-table__row" key={feature.id}><span><strong>{feature.source_id}</strong><small>{shortId(feature.id)}</small></span><span>{feature.kind}</span><span>{feature.source_layer}</span><span>{feature.quality_flags.length ? feature.quality_flags.join(", ") : "Без flags"}</span><span>{feature.source_type}</span></div>)}
                  </div>
                )}
              </Card>
            </>
          )}
        </section>
      </div>

      <Dialog open={uploadOpen} onClose={() => setUploadOpen(false)} title="Новая поставка" description="Файл сохранится как неизменяемый raw-артефакт. Публикация начнётся только после проверки.">
        <form className="dialog-form" onSubmit={submitUpload}>
          <label className="upload-drop"><FileUp size={22} /><strong>Выберите файл</strong><span>GeoJSON, GeoPackage или CSV · до лимита сервера</span><input type="file" name="file" required accept=".geojson,.json,.gpkg,.csv,application/geo+json,application/geopackage+sqlite3,text/csv" /></label>
          <Field label="Название набора"><Input name="dataset_name" defaultValue="Здания квартала" required /></Field>
          <Field label="Назначение"><select className="select" name="purpose" defaultValue="planning"><option value="planning">Планирование</option><option value="obstacles">Препятствия</option><option value="network">Существующая сеть</option></select></Field>
          <Field label="Лицензия / условия использования"><Input name="license_note" placeholder="Например, предоставлено организатором" /></Field>
          <div className="dialog-actions"><Button type="button" variant="ghost" onClick={() => setUploadOpen(false)}>Отмена</Button><Button type="submit" disabled={upload.isPending}>{upload.isPending ? "Загружаем…" : <>Загрузить <ArrowRight size={15} /></>}</Button></div>
        </form>
      </Dialog>
    </div>
  );
}
