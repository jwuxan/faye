/** Where the appearance is kept, in localStorage. */
export const APPEARANCE_KEY = "faye-appearance"

/**
 * Sets data-theme on <html> to light or dark before first paint, from the saved appearance or the
 * OS, and again whenever the OS or another tab changes it. Each app's root layout puts it in <head>.
 * It also sets the class "dark", which the admin's docs site (Fumadocs) keys its dark theme on.
 */
export const APPEARANCE_SCRIPT = `(() => {
  const os = matchMedia("(prefers-color-scheme: dark)");
  const apply = () => {
    let a = null;
    try { a = localStorage.getItem("${APPEARANCE_KEY}"); } catch {}
    const theme = a === "light" || a === "dark" ? a : os.matches ? "dark" : "light";
    document.documentElement.dataset.theme = theme;
    document.documentElement.classList.toggle("dark", theme === "dark");
  };
  apply();
  os.addEventListener("change", apply);
  addEventListener("storage", (e) => e.key === "${APPEARANCE_KEY}" && apply());
})()`
