import {
  AlertTriangle,
  CheckCircle2,
  CircleDot,
  LoaderCircle,
  RotateCcw,
  X,
} from "lucide-react";
import type { HTMLAttributes, InputHTMLAttributes, ReactNode } from "react";

import { cn } from "../../shared/cn";
import { Button } from "./button";

export function Badge({
  tone = "neutral",
  className,
  ...props
}: HTMLAttributes<HTMLSpanElement> & {
  tone?: "neutral" | "success" | "warning" | "danger" | "info" | "violet";
}) {
  return <span className={cn("badge", `badge--${tone}`, className)} {...props} />;
}

export function Card({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("card", className)} {...props} />;
}

export function Input({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return <input className={cn("input", className)} {...props} />;
}

export function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: ReactNode;
}) {
  return (
    <label className="field">
      <span className="field__label">{label}</span>
      {children}
      {hint && <small>{hint}</small>}
    </label>
  );
}

export function StateView({
  state,
  title,
  detail,
  onRetry,
  compact = false,
}: {
  state: "loading" | "empty" | "error";
  title: string;
  detail?: string;
  onRetry?: () => void;
  compact?: boolean;
}) {
  const Icon = state === "loading" ? LoaderCircle : state === "error" ? AlertTriangle : CircleDot;
  return (
    <div className={cn("state-view", compact && "state-view--compact")} role={state === "error" ? "alert" : "status"}>
      <span className={cn("state-view__icon", state === "loading" && "is-spinning")}><Icon size={19} /></span>
      <strong>{title}</strong>
      {detail && <p>{detail}</p>}
      {onRetry && <Button variant="outline" onClick={onRetry}><RotateCcw size={14} /> Повторить</Button>}
    </div>
  );
}

export function StatusBadge({ value }: { value: string }) {
  const normalized = value.toLowerCase();
  const success = ["succeeded", "published", "ready_to_publish", "valid", "valid_in_model", "known_complete", "routes_found", "complete"];
  const danger = ["failed", "rejected", "invalid_in_model"];
  const warning = ["partial", "needs_review", "insufficient_data", "cancel_requested", "unknown", "unavailable"];
  const active = ["queued", "running", "inspecting", "validating", "publishing"];
  const tone = success.includes(normalized)
    ? "success"
    : danger.includes(normalized)
      ? "danger"
      : warning.includes(normalized)
        ? "warning"
        : active.includes(normalized)
          ? "info"
          : "neutral";
  const labels: Record<string, string> = {
    succeeded: "Готово",
    valid: "Валидно",
    published: "Опубликовано",
    ready_to_publish: "Готово к публикации",
    valid_in_model: "Допустимо в модели",
    failed: "Ошибка",
    rejected: "Отклонено",
    invalid_in_model: "Недопустимо",
    partial: "Частичный результат",
    needs_review: "Нужна проверка",
    insufficient_data: "Недостаточно данных",
    cancel_requested: "Отмена запрошена",
    cancelled: "Отменено",
    queued: "В очереди",
    running: "Выполняется",
    inspecting: "Инспекция",
    validating: "Проверка",
    publishing: "Публикация",
    mapping_required: "Нужно сопоставление",
    ready_to_validate: "Готово к проверке",
    uploaded: "Загружено",
    routes_found: "Маршруты найдены",
    no_route: "Маршрут не найден",
    complete: "Полная оценка",
    unavailable: "Нет оценки",
    pending: "Ожидает",
    draft: "Черновик",
    demo: "Демо",
    synthetic: "Синтетика",
    provided: "Поставка",
    mixed: "Смешанный",
    unknown: "Неизвестно",
  };
  return <Badge tone={tone}>{labels[normalized] ?? value.replaceAll("_", " ")}</Badge>;
}

export function ProgressBar({ current, total, label }: { current?: number | null; total?: number | null; label: string }) {
  const known = typeof current === "number" && typeof total === "number" && total > 0;
  const percent = known ? Math.min(100, Math.round((current / total) * 100)) : undefined;
  return (
    <div className="progress-block" aria-label={label}>
      <div><span>{label}</span><strong>{percent === undefined ? "Выполняется" : `${percent}%`}</strong></div>
      <div className={cn("progress-track", percent === undefined && "progress-track--indeterminate")}>
        {percent !== undefined && <i style={{ width: `${percent}%` }} />}
      </div>
    </div>
  );
}

export function Dialog({
  open,
  title,
  description,
  children,
  onClose,
}: {
  open: boolean;
  title: string;
  description?: string;
  children: ReactNode;
  onClose: () => void;
}) {
  if (!open) return null;
  return (
    <div className="dialog-backdrop" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <section className="dialog" role="dialog" aria-modal="true" aria-labelledby="dialog-title">
        <header>
          <div><h2 id="dialog-title">{title}</h2>{description && <p>{description}</p>}</div>
          <button className="icon-button" type="button" aria-label="Закрыть" onClick={onClose}><X size={17} /></button>
        </header>
        {children}
      </section>
    </div>
  );
}

export function CheckLine({ ok, children }: { ok: boolean; children: ReactNode }) {
  return <span className={cn("check-line", ok && "check-line--ok")}>{ok ? <CheckCircle2 size={15} /> : <AlertTriangle size={15} />}{children}</span>;
}
