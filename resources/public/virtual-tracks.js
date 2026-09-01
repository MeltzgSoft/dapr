/* Bounded, bidirectional virtual scrolling for the server-rendered track table.
 * The server owns sorting/filtering and sends a <tbody> window; this controller
 * only decides which zero-based window should be visible. */
(() => {
  "use strict";

  const ROW_HEIGHT = 31;
  const WINDOW_SIZE = 200;
  const OVERSCAN = 50;
  const STEP = 50;

  function integer(value, fallback = 0) {
    const parsed = Number.parseInt(value, 10);
    return Number.isFinite(parsed) ? parsed : fallback;
  }

  function syncViewState(section, body) {
    const start = integer(body.dataset.start);
    const form = section.querySelector("#track-view");
    const hiddenStart = form?.querySelector('input[name="start"]');
    const hiddenSort = form?.querySelector('input[name="sort"]');
    const hiddenDir = form?.querySelector('input[name="dir"]');
    if (hiddenStart) hiddenStart.value = String(start);
    if (hiddenSort) hiddenSort.value = body.dataset.sort || "";
    if (hiddenDir) hiddenDir.value = body.dataset.dir || "asc";

    const pollUrl = new URL(section.getAttribute("hx-get"), window.location.href);
    pollUrl.searchParams.set("start", String(start));
    if (body.dataset.sort) pollUrl.searchParams.set("sort", body.dataset.sort);
    else pollUrl.searchParams.delete("sort");
    pollUrl.searchParams.set("dir", body.dataset.dir || "asc");
    if (body.dataset.digest) pollUrl.searchParams.set("d", body.dataset.digest);
    section.setAttribute("hx-get", `${pollUrl.pathname}${pollUrl.search}`);
  }

  async function loadWindow(section, desiredStart) {
    const body = section.querySelector("tbody");
    if (!body) return;
    if (integer(body.dataset.start) === desiredStart) return;
    if (section._trackWindowStart === desiredStart) return;

    section.classList.add("loading-window");
    const controller = new AbortController();
    if (section._trackWindowController) section._trackWindowController.abort();
    section._trackWindowController = controller;
    section._trackWindowStart = desiredStart;
    const requestedStateDigest = body.dataset.stateDigest;

    try {
      const url = new URL(section.dataset.windowUrl, window.location.href);
      url.searchParams.set("start", String(desiredStart));
      // The body is the exact view currently visible. A morph may preserve an
      // older shell attribute, so never let that stale URL choose the ordering.
      if (body.dataset.sort) url.searchParams.set("sort", body.dataset.sort);
      else url.searchParams.delete("sort");
      url.searchParams.set("dir", body.dataset.dir || "asc");
      const response = await fetch(url, {
        headers: {"HX-Request": "true"},
        signal: controller.signal
      });
      if (!response.ok) throw new Error(`Track window request failed: ${response.status}`);

      const parsed = new DOMParser().parseFromString(
        `<table>${(await response.text()).trim()}</table>`, "text/html");
      const parsedBody = parsed.querySelector("tbody");
      const nextBody = parsedBody && document.importNode(parsedBody, true);
      if (!nextBody || !body.isConnected) return;

      const liveBody = section.querySelector("tbody");
      if (liveBody?.dataset.stateDigest !== requestedStateDigest &&
          nextBody.dataset.stateDigest === requestedStateDigest) return;

      liveBody.replaceWith(nextBody);
      syncViewState(section, nextBody);
      if (window.htmx) window.htmx.process(nextBody);
    } catch (error) {
      if (error.name !== "AbortError") console.error(error);
    } finally {
      if (section._trackWindowController === controller) {
        section._trackWindowController = null;
        section._trackWindowStart = null;
        section.classList.remove("loading-window");
      }
    }
  }

  function desiredWindow(scroll, body) {
    const total = integer(body.dataset.total);
    if (total <= WINDOW_SIZE) return 0;

    const header = scroll.querySelector("thead");
    const headerHeight = header ? header.getBoundingClientRect().height : 0;
    const firstVisible = Math.max(0, Math.floor((scroll.scrollTop - headerHeight) / ROW_HEIGHT));
    const visible = Math.max(1, Math.ceil(scroll.clientHeight / ROW_HEIGHT));
    const currentStart = integer(body.dataset.start);
    const currentEnd = integer(body.dataset.end);

    if (firstVisible >= currentStart + OVERSCAN &&
        firstVisible + visible <= currentEnd - OVERSCAN) return currentStart;

    const maxStart = Math.max(0, total - WINDOW_SIZE);
    const centered = Math.max(0, firstVisible - Math.floor((WINDOW_SIZE - visible) / 2));
    return Math.min(maxStart, Math.floor(centered / STEP) * STEP);
  }

  function initialize(section) {
    const body = section.querySelector("tbody");
    if (!body) return;
    syncViewState(section, body);
  }

  function initializeAll(root = document) {
    if (root.matches && root.matches("#track-table")) initialize(root);
    root.querySelectorAll?.("#track-table").forEach(initialize);
  }

  document.addEventListener("DOMContentLoaded", () => initializeAll());
  // The event target may be the control that initiated a swap rather than the
  // newly inserted table, so rediscover by its stable id after every settle.
  document.addEventListener("htmx:afterSettle", () => initializeAll());

  // Scroll events do not bubble, but capture reaches replaced scroll containers.
  // Keeping this listener on document makes virtual scrolling survive HTMX swaps.
  document.addEventListener("scroll", event => {
    const scroll = event.target;
    if (!(scroll instanceof HTMLElement) || scroll.id !== "track-scroll") return;
    if (scroll._trackWindowFrame != null) return;

    scroll._trackWindowFrame = window.requestAnimationFrame(() => {
      scroll._trackWindowFrame = null;
      const section = scroll.closest("#track-table");
      const body = section?.querySelector("tbody");
      if (section && body) loadWindow(section, desiredWindow(scroll, body));
    });
  }, {capture: true, passive: true});
})();
