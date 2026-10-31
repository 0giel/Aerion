import "./app.css";
import { mount } from "svelte";
import App from "./App.svelte";
import { themes } from "./lib/theme/engine.svelte";
import { commands } from "./lib/bindings";

// Report uncaught frontend errors to the launcher log.
const report = (msg: string) => void commands.frontendLog("error", msg).catch(() => {});
window.addEventListener("error", (e) => report(`${e.message} @ ${e.filename}:${e.lineno}`));
window.addEventListener("unhandledrejection", (e) => report(`unhandled rejection: ${String(e.reason?.stack ?? e.reason)}`));

// Paint with the default theme immediately; the saved theme follows once settings load.
themes.apply();

const app = mount(App, { target: document.getElementById("app")! });

export default app;
