// Where theme videos come from on Linux and macOS: a local address the launcher opens on first
// use (its window can't play videos from the themeasset scheme). Null until then, and on Windows.
export const media = $state<{ base: string | null }>({ base: null });
