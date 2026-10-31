import en, { type Dict, type Plural } from "./en";
import pl from "./pl";

const dicts: Record<Locale, Dict> = { en, pl };
export type Locale = "en" | "pl";
export const LOCALES: { id: Locale; name: string }[] = [
  { id: "pl", name: "Polski" },
  { id: "en", name: "English" },
];

export function systemLocale(): Locale {
  const langs = navigator.languages?.length ? navigator.languages : [navigator.language];
  return langs.some((l) => l?.toLowerCase().startsWith("pl")) ? "pl" : "en";
}

class I18n {
  locale = $state<Locale>(systemLocale());

  set(pref: string | null | undefined) {
    this.locale = pref === "pl" || pref === "en" ? pref : systemLocale();
    document.documentElement.lang = this.locale;
  }
}

export const i18n = new I18n();

type Params = Record<string, string | number | null | undefined>;

function lookup(dict: unknown, key: string): unknown {
  let cur: unknown = dict;
  for (const part of key.split(".")) {
    if (cur && typeof cur === "object" && part in (cur as object))
      cur = (cur as Record<string, unknown>)[part];
    else return undefined;
  }
  return cur;
}

function interpolate(s: string, params?: Params): string {
  if (!params) return s;
  return s.replace(/\{(\w+)\}/g, (m, k) =>
    params[k] !== undefined && params[k] !== null ? String(params[k]) : m,
  );
}

const pluralRules: Record<string, Intl.PluralRules> = {};

/** Translate `key` (dot path). Plural entries use `params.n`. Falls back to English, then the key. */
export function t(key: string, params?: Params): string {
  const locale = i18n.locale;
  let v = lookup(dicts[locale], key) ?? lookup(en, key);
  if (v && typeof v === "object" && "other" in (v as object)) {
    const n = Number(params?.n ?? 0);
    const rules = (pluralRules[locale] ??= new Intl.PluralRules(locale));
    const p = v as Plural;
    v = p[rules.select(n) as keyof Plural] ?? p.other;
  }
  return typeof v === "string" ? interpolate(v, params) : key;
}

/** Whether a translation exists (used for error/crash codes coming from Rust). */
export function has(key: string): boolean {
  return typeof lookup(en, key) === "string";
}

export function formatNumber(n: number, compact = false): string {
  return new Intl.NumberFormat(
    i18n.locale,
    compact ? { notation: "compact", maximumFractionDigits: 1 } : {},
  ).format(n);
}

export function formatBytes(bytes: number): string {
  const units = ["B", "KB", "MB", "GB", "TB"];
  let v = bytes;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${new Intl.NumberFormat(i18n.locale, { maximumFractionDigits: v < 10 && i > 0 ? 1 : 0 }).format(v)} ${units[i]}`;
}

/** "5 minutes ago" style; `when` in seconds or an ISO string. */
export function formatRelative(when: number | string | null | undefined): string {
  if (when === null || when === undefined || when === "") return t("time.never");
  const ms = typeof when === "number" ? when * 1000 : Date.parse(when);
  if (Number.isNaN(ms)) return t("time.never");
  const diff = Math.max(0, (Date.now() - ms) / 1000);
  if (diff < 60) return t("time.justNow");
  if (diff < 3600) return t("time.minutesAgo", { n: Math.floor(diff / 60) });
  if (diff < 86400) return t("time.hoursAgo", { n: Math.floor(diff / 3600) });
  if (diff < 86400 * 30) return t("time.daysAgo", { n: Math.floor(diff / 86400) });
  return new Intl.DateTimeFormat(i18n.locale, { dateStyle: "medium" }).format(ms);
}

export function formatDate(when: string | number): string {
  const ms = typeof when === "number" ? when * 1000 : Date.parse(when);
  return Number.isNaN(ms) ? "" : new Intl.DateTimeFormat(i18n.locale, { dateStyle: "medium" }).format(ms);
}

export function formatPlayTime(secs: number): string {
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  return h > 0 ? t("time.playTime", { h, m }) : t("time.playTimeMinutes", { m });
}
