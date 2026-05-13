const state = {
  collections: [],
  selected: null,
};

const $ = (id) => document.getElementById(id);

function loadConfig() {
  $("baseUrl").value = localStorage.getItem("anaxa.baseUrl") || window.location.origin;
  $("apiKey").value = localStorage.getItem("anaxa.apiKey") || "";
  $("tenantId").value = localStorage.getItem("anaxa.tenantId") || "";
}

function saveConfig() {
  localStorage.setItem("anaxa.baseUrl", normalizedBaseUrl());
  localStorage.setItem("anaxa.apiKey", $("apiKey").value.trim());
  localStorage.setItem("anaxa.tenantId", $("tenantId").value.trim());
  setStatus("Saved", "ok");
}

function normalizedBaseUrl() {
  return ($("baseUrl").value.trim() || window.location.origin).replace(/\/+$/, "");
}

function headers(hasBody = false) {
  const value = {};
  const apiKey = $("apiKey").value.trim();
  const tenantId = $("tenantId").value.trim();
  if (hasBody) value["Content-Type"] = "application/json";
  if (apiKey) value["X-API-Key"] = apiKey;
  if (tenantId) value["X-Tenant-Id"] = tenantId;
  return value;
}

async function api(method, path, body) {
  const started = performance.now();
  const url = normalizedBaseUrl() + path;
  const response = await fetch(url, {
    method,
    headers: headers(body !== undefined),
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  const elapsed = performance.now() - started;
  const traceId = response.headers.get("X-Trace-Id") || "";
  showLastResponse({ method, url, body, status: response.status, traceId, elapsed, text });
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${text}`);
  return parseText(text);
}

function parseText(text) {
  if (!text) return null;
  try { return JSON.parse(text); } catch { return text; }
}

function jsonFrom(id) {
  const text = $(id).value.trim();
  if (!text) return {};
  return JSON.parse(text);
}

function vectorFrom(id) {
  const values = $(id).value.split(/[\s,]+/).filter(Boolean).map(Number);
  if (values.some((value) => Number.isNaN(value))) throw new Error("Vector contains non-number values");
  if (values.length === 0) throw new Error("Vector must not be empty");
  return values;
}

function selectedName() {
  if (!state.selected) throw new Error("Select a collection first");
  return state.selected.name;
}

function setStatus(text, kind = "muted") {
  const pill = $("statusPill");
  pill.textContent = text;
  pill.className = `status-pill ${kind}`;
}

function showLastResponse(entry) {
  $("lastResponse").textContent = JSON.stringify({
    request: { method: entry.method, url: entry.url, body: entry.body },
    response: { status: entry.status, traceId: entry.traceId, elapsedMs: Number(entry.elapsed.toFixed(2)), body: parseText(entry.text) },
  }, null, 2);
}

function pretty(value) {
  return JSON.stringify(value, null, 2);
}

function randomVector(dimension) {
  return Array.from({ length: dimension }, () => Number((Math.random() * 2 - 1).toFixed(6)));
}

function collectionDimension() {
  return state.selected?.dimension || Number($("createDimension").value) || Number($("batchDimension").value) || 3;
}

async function refreshCollections() {
  const collections = await api("GET", "/collections");
  state.collections = Array.isArray(collections) ? collections : [];
  if (state.selected) {
    state.selected = state.collections.find((item) => item.name === state.selected.name && item.tenantId === state.selected.tenantId) || state.selected;
  }
  renderCollections();
  renderSelectedStats();
}

function renderCollections() {
  const root = $("collectionList");
  root.innerHTML = "";
  if (state.collections.length === 0) {
    root.innerHTML = '<p class="hint">No collections.</p>';
    return;
  }
  for (const item of state.collections) {
    const div = document.createElement("div");
    div.className = "collection-item" + (state.selected?.name === item.name && state.selected?.tenantId === item.tenantId ? " active" : "");
    div.innerHTML = `<div class="collection-name"></div><div class="collection-meta"></div>`;
    div.querySelector(".collection-name").textContent = item.name;
    div.querySelector(".collection-meta").textContent = `${item.tenantId || "default"} · dim ${item.dimension} · ${item.metric} · live ${item.liveVectorCount}`;
    div.addEventListener("click", () => {
      state.selected = item;
      $("batchDimension").value = item.dimension;
      $("restoreSource").value = item.name;
      renderCollections();
      renderSelectedStats();
    });
    root.appendChild(div);
  }
}

function renderSelectedStats() {
  $("selectedStats").textContent = state.selected ? pretty(state.selected) : "No collection selected.";
}

async function run(action) {
  try {
    await action();
    setStatus("OK", "ok");
  } catch (error) {
    setStatus("Failed", "fail");
    $("lastResponse").textContent = error.stack || String(error);
  }
}

function bindTabs() {
  document.querySelectorAll(".tab").forEach((tab) => {
    tab.addEventListener("click", () => {
      document.querySelectorAll(".tab").forEach((item) => item.classList.remove("active"));
      document.querySelectorAll(".panel").forEach((item) => item.classList.remove("active"));
      tab.classList.add("active");
      $(`tab-${tab.dataset.tab}`).classList.add("active");
    });
  });
}

function bindActions() {
  $("saveConfig").addEventListener("click", saveConfig);
  $("checkHealth").addEventListener("click", () => run(async () => { await api("GET", "/health"); }));
  $("refreshCollections").addEventListener("click", () => run(refreshCollections));
  $("createCollection").addEventListener("click", () => run(async () => {
    await api("POST", "/collections", {
      name: $("createName").value.trim(),
      dimension: Number($("createDimension").value),
      metric: $("createMetric").value,
      flushThresholdBytes: Number($("createFlushThreshold").value),
    });
    await refreshCollections();
  }));
  $("randomSingleVector").addEventListener("click", () => { $("singleVector").value = randomVector(collectionDimension()).join(","); });
  $("randomSearchVector").addEventListener("click", () => { $("searchVector").value = randomVector(collectionDimension()).join(","); });
  $("upsertSingle").addEventListener("click", () => run(async () => {
    await api("POST", `/collections/${encodeURIComponent(selectedName())}/vectors`, { vectors: [{ id: $("singleId").value.trim(), vector: vectorFrom("singleVector"), payload: jsonFrom("singlePayload") }] });
    await refreshCollections();
  }));
  $("upsertBatch").addEventListener("click", () => run(async () => {
    const prefix = $("batchPrefix").value.trim() || "doc";
    const count = Number($("batchCount").value);
    const dimension = Number($("batchDimension").value);
    const payload = jsonFrom("batchPayload");
    const vectors = Array.from({ length: count }, (_, index) => ({ id: `${prefix}-${Date.now()}-${index}`, vector: randomVector(dimension), payload: { ...payload, ordinal: index } }));
    await api("POST", `/collections/${encodeURIComponent(selectedName())}/vectors`, { vectors });
    await refreshCollections();
  }));
  $("searchCollection").addEventListener("click", () => run(async () => {
    const started = performance.now();
    const result = await api("POST", `/collections/${encodeURIComponent(selectedName())}/search`, { vector: vectorFrom("searchVector"), topK: Number($("topK").value), filter: jsonFrom("filterJson") });
    renderHits(result?.hits || [], performance.now() - started);
  }));
  $("partialUpdate").addEventListener("click", () => run(async () => { await api("PATCH", `/collections/${encodeURIComponent(selectedName())}/vectors`, jsonFrom("updatesJson")); await refreshCollections(); }));
  $("deleteVectors").addEventListener("click", () => run(async () => {
    const ids = $("deleteIds").value.split(/[\s,]+/).filter(Boolean);
    await api("POST", `/collections/${encodeURIComponent(selectedName())}/deletions`, { ids });
    await refreshCollections();
  }));
  $("flushCollection").addEventListener("click", () => run(async () => { await api("POST", `/collections/${encodeURIComponent(selectedName())}/flush`, {}); await refreshCollections(); }));
  $("compactCollection").addEventListener("click", () => run(async () => { await api("POST", `/collections/${encodeURIComponent(selectedName())}/compact`, {}); await refreshCollections(); }));
  $("getCollectionStats").addEventListener("click", () => run(async () => { state.selected = await api("GET", `/collections/${encodeURIComponent(selectedName())}`); renderSelectedStats(); }));
  $("backupCollection").addEventListener("click", () => run(async () => { await api("POST", `/collections/${encodeURIComponent(selectedName())}/backup`, { backupId: $("backupId").value.trim() }); }));
  $("listBackups").addEventListener("click", () => run(async () => { showLastResponse({ method: "GET", url: normalizedBaseUrl() + "/backups", body: undefined, status: 200, traceId: "", elapsed: 0, text: JSON.stringify(await api("GET", "/backups")) }); }));
  $("loadMetrics").addEventListener("click", () => run(async () => { await api("GET", "/metrics"); }));
  $("restoreCollection").addEventListener("click", () => run(async () => {
    await api("POST", `/backups/${encodeURIComponent($("restoreBackupId").value.trim())}/restore`, { sourceCollection: $("restoreSource").value.trim(), collectionName: $("restoreTarget").value.trim() });
    await refreshCollections();
  }));
  $("sendRaw").addEventListener("click", () => run(async () => {
    const bodyText = $("rawBody").value.trim();
    await api($("rawMethod").value, $("rawPath").value.trim(), bodyText ? JSON.parse(bodyText) : undefined);
  }));
}

function renderHits(hits, elapsed) {
  $("searchSummary").textContent = `${hits.length} hits · ${elapsed.toFixed(2)} ms`;
  const body = $("hitsBody");
  body.innerHTML = "";
  hits.forEach((hit, index) => {
    const row = document.createElement("tr");
    row.innerHTML = `<td>${index + 1}</td><td></td><td>${Number(hit.score).toFixed(6)}</td><td>${hit.sequence ?? ""}</td><td class="payload"></td>`;
    row.children[1].textContent = hit.id;
    row.querySelector(".payload").textContent = pretty(hit.payload || {});
    body.appendChild(row);
  });
}

loadConfig();
bindTabs();
bindActions();
refreshCollections().catch(() => setStatus("Need connection", "muted"));
