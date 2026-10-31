<script lang="ts">
  // Renders untrusted Markdown/HTML from mod pages. Everything goes through DOMPurify;
  // images are routed through the image proxy, links open in the system browser.
  import DOMPurify from "dompurify";
  import { marked } from "marked";
  import { openUrl } from "@tauri-apps/plugin-opener";
  import { imgUrl } from "../api";

  let {
    source,
    format = "markdown",
    noCache = false,
  }: { source: string; format?: "markdown" | "html"; noCache?: boolean } = $props();

  const html = $derived.by(() => {
    const raw =
      format === "markdown"
        ? (marked.parse(source, { async: false, gfm: true, breaks: false }) as string)
        : source;
    DOMPurify.removeAllHooks();
    DOMPurify.addHook("afterSanitizeAttributes", (node) => {
      if (node.tagName === "IMG") {
        const src = node.getAttribute("src");
        const proxied = imgUrl(src, { noCache });
        if (proxied) node.setAttribute("src", proxied);
        else node.removeAttribute("src");
        node.setAttribute("loading", "lazy");
        node.removeAttribute("srcset");
      }
      if (node.tagName === "A") {
        node.setAttribute("rel", "noopener noreferrer");
      }
    });
    const clean = DOMPurify.sanitize(raw, {
      FORBID_TAGS: [
        "style",
        "iframe",
        "form",
        "input",
        "button",
        "object",
        "embed",
        "video",
        "audio",
        "svg",
        "math",
      ],
      FORBID_ATTR: ["style", "class", "id"],
      ALLOW_DATA_ATTR: false,
    });
    DOMPurify.removeAllHooks();
    return clean;
  });

  function onclick(e: MouseEvent) {
    const a = (e.target as HTMLElement).closest("a");
    if (!a) return;
    e.preventDefault();
    const href = a.getAttribute("href") ?? "";
    if (/^https?:\/\//i.test(href)) void openUrl(href);
  }
</script>

<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
<!-- eslint-disable-next-line svelte/no-at-html-tags -- sanitized by DOMPurify above -->
<div class="markdown selectable" {onclick}>{@html html}</div>

<style>
  .markdown {
    line-height: 1.65;
    overflow-wrap: anywhere;
  }
  .markdown :global(h1),
  .markdown :global(h2),
  .markdown :global(h3) {
    margin: 1.2em 0 0.5em;
  }
  .markdown :global(p),
  .markdown :global(ul),
  .markdown :global(ol) {
    margin: 0.6em 0;
  }
  .markdown :global(img) {
    max-width: 100%;
    height: auto;
    border-radius: var(--r-sm);
  }
  .markdown :global(pre),
  .markdown :global(code) {
    font-family: var(--font-mono);
    font-size: 0.88em;
    background: var(--c-hover);
    border-radius: var(--r-sm);
  }
  .markdown :global(code) {
    padding: 0.1em 0.35em;
  }
  .markdown :global(pre) {
    padding: 0.8em;
    overflow: auto;
  }
  .markdown :global(blockquote) {
    margin: 0.8em 0;
    padding-left: 1em;
    border-left: 3px solid var(--c-border);
    color: var(--c-text-muted);
  }
  .markdown :global(table) {
    border-collapse: collapse;
  }
  .markdown :global(td),
  .markdown :global(th) {
    border: 1px solid var(--c-border);
    padding: 0.3em 0.6em;
  }
  .markdown :global(hr) {
    border: 0;
    border-top: 1px solid var(--c-border);
  }
</style>
