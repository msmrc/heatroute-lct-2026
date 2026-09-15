export function formatNumber(value: unknown, maximumFractionDigits = 0): string {
  if (typeof value !== "number" || !Number.isFinite(value)) return "—";
  return new Intl.NumberFormat("ru-RU", { maximumFractionDigits }).format(value);
}

export function formatMoney(value: unknown): string {
  const numeric = typeof value === "number"
    ? value
    : typeof value === "string" && value.trim() !== ""
      ? Number(value)
      : Number.NaN;
  if (!Number.isFinite(numeric)) return "Не оценено";
  return new Intl.NumberFormat("ru-RU", {
    style: "currency",
    currency: "RUB",
    maximumFractionDigits: 0,
  }).format(numeric);
}

export function formatDate(value: string | null | undefined, withTime = true): string {
  if (!value) return "—";
  return new Intl.DateTimeFormat("ru-RU", {
    day: "2-digit",
    month: "short",
    year: "numeric",
    ...(withTime ? { hour: "2-digit", minute: "2-digit" } : {}),
  }).format(new Date(value));
}

export function shortId(value: string | null | undefined): string {
  return value ? value.slice(0, 8) : "—";
}

export function errorText(error: unknown): string {
  return error instanceof Error ? error.message : "Неизвестная ошибка";
}

export function displayValue(value: unknown, fallback = "—"): string {
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean" || typeof value === "bigint") {
    return String(value);
  }
  if (value === null || value === undefined) return fallback;
  return JSON.stringify(value);
}

export function formText(form: FormData, key: string, fallback = ""): string {
  const value = form.get(key);
  return typeof value === "string" ? value : fallback;
}
