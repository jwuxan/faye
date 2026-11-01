/** Where the appearance is kept, in localStorage. */
export const APPEARANCE_KEY = "faye-appearance"

/**
 * Sets data-theme on <html> to light or dark before first paint, from the saved appearance or the
 * OS, and again whenever the OS or another tab changes it. Each app's root layout puts it in <head>.
 */
export const APPEARANCE_SCRIPT = `(() => {
  const os = matchMedia("(prefers-color-scheme: dark)");
  const apply = () => {
    let a = null;
    try { a = localStorage.getItem("${APPEARANCE_KEY}"); } catch {}
    document.documentElement.dataset.theme = a === "light" || a === "dark" ? a : os.matches ? "dark" : "light";
  };
  apply();
  os.addEventListener("change", apply);
  addEventListener("storage", (e) => e.key === "${APPEARANCE_KEY}" && apply());
})()`
