"use strict";

const state = {
  tenant: sessionStorage.getItem("br.tenant") || "",
  apiToken: sessionStorage.getItem("br.apiToken") || "",
  ingestToken: sessionStorage.getItem("br.ingestToken") || "",
  installations: [],
  mappings: [],
  dependencies: []
};

let toastTimer;
let githubPollTimer;
const byId = (id) => document.getElementById(id);

function authHeaders(extra) {
  const headers = new Headers(extra || {});
  headers.set("X-Tenant-ID", state.tenant || "default");
  if (state.apiToken) headers.set("Authorization", "Bearer " + state.apiToken);
  return headers;
}

async function api(path, options) {
  const opts = options || {};
  const response = await fetch(path, {
    ...opts,
    headers: authHeaders(opts.headers),
    credentials: "same-origin"
  });

  const text = await response.text();
  let data = null;
  if (text) {
    try { data = JSON.parse(text); } catch { data = text; }
  }

  if (!response.ok) {
    const message =
      (data && typeof data === "object" && (data.detail || data.message || data.error)) ||
      (typeof data === "string" && data) ||
      "Request failed (" + response.status + ")";
    throw new Error(message);
  }

  return data;
}

function saveCredentials(tenant, apiToken, ingestToken) {
  state.tenant = tenant.trim() || "default";
  state.apiToken = apiToken.trim();
  state.ingestToken = ingestToken.trim();
  sessionStorage.setItem("br.tenant", state.tenant);
  sessionStorage.setItem("br.apiToken", state.apiToken);
  sessionStorage.setItem("br.ingestToken", state.ingestToken);
  byId("tenantBadge").textContent = state.tenant;
  renderTelemetrySnippet();
}

function forgetCredentials() {
  ["br.tenant", "br.apiToken", "br.ingestToken"].forEach((key) => sessionStorage.removeItem(key));
  state.tenant = "";
  state.apiToken = "";
  state.ingestToken = "";
  state.installations = [];
  state.mappings = [];
  state.dependencies = [];
  byId("tenantInput").value = "default";
  byId("apiTokenInput").value = "";
  byId("ingestTokenInput").value = "";
  byId("credentialGate").classList.remove("hidden");
  showToast("Credentials cleared from this browser session.");
}

function showToast(message, isError) {
  const toast = byId("toast");
  toast.textContent = message;
  toast.classList.toggle("error", Boolean(isError));
  toast.classList.remove("hidden");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.add("hidden"), 4200);
}

function setButtonBusy(button, busy, busyText) {
  if (!button) return;
  if (busy) {
    button.dataset.originalText = button.textContent;
    button.textContent = busyText;
    button.disabled = true;
  } else {
    button.textContent = button.dataset.originalText || button.textContent;
    button.disabled = false;
  }
}

function formatDate(value) {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(date);
}

function makeStackItem(title, detail, trailing) {
  const item = document.createElement("div");
  item.className = "stack-item";
  const main = document.createElement("div");
  main.className = "stack-item-main";
  const strong = document.createElement("strong");
  strong.textContent = title;
  const span = document.createElement("span");
  span.textContent = detail;
  main.append(strong, span);
  item.append(main);
  if (trailing) item.append(trailing);
  return item;
}

function statusDot(status) {
  const dot = document.createElement("span");
  const active = String(status || "").toUpperCase() === "ACTIVE";
  dot.className = "status-dot " + (active ? "active" : "inactive");
  dot.title = status || "Unknown";
  return dot;
}

async function refreshAll(showSuccess) {
  const button = byId("refreshButton");
  setButtonBusy(button, true, "Refreshing…");
  const results = await Promise.allSettled([
    loadInstallations(),
    loadMappings(),
    loadDependencies()
  ]);
  updateOverview();

  const failures = results.filter((result) => result.status === "rejected");
  if (failures.length) {
    const first = failures[0].reason;
    showToast(first instanceof Error ? first.message : "Some workspace data could not be loaded.", true);
  } else if (showSuccess) {
    showToast("Workspace refreshed.");
  }
  setButtonBusy(button, false);
}

async function loadInstallations() {
  const value = await api("/api/v1/onboarding/github/installations");
  state.installations = Array.isArray(value) ? value : [];
  renderInstallations();
  return state.installations;
}

function renderInstallations() {
  const container = byId("githubInstallations");
  container.replaceChildren();
  if (!state.installations.length) {
    container.className = "stack-list empty-state";
    container.textContent = "No bound installations yet.";
    return;
  }

  container.className = "stack-list";
  state.installations.forEach((installation) => {
    const detail =
      (installation.accountType || "Account") +
      " · installation #" + installation.installationId +
      " · " + (installation.status || "UNKNOWN");
    container.append(
      makeStackItem(
        installation.accountLogin || "GitHub account",
        detail,
        statusDot(installation.status)
      )
    );
  });
}

async function connectGitHub() {
  const button = byId("connectGitHubButton");
  const popup = window.open("about:blank", "prBlastRadiusGitHub", "popup,width=780,height=760");
  setButtonBusy(button, true, "Starting…");

  try {
    const result = await api("/api/v1/onboarding/github/install", { method: "POST" });
    if (!result || !result.installUrl) throw new Error("GitHub installation URL was not returned.");

    if (popup) {
      popup.location.replace(result.installUrl);
      popup.focus();
      startGitHubPolling();
    } else {
      window.location.assign(result.installUrl);
    }
  } catch (error) {
    if (popup) popup.close();
    showToast(error.message || "Could not start GitHub installation.", true);
  } finally {
    setButtonBusy(button, false);
  }
}


function startGitHubPolling() {
  window.clearInterval(githubPollTimer);

  let attempts = 0;
  githubPollTimer = window.setInterval(async () => {
    attempts += 1;

    try {
      const installations = await api(
        "/api/v1/onboarding/github/installations"
      );

      state.installations = Array.isArray(installations)
        ? installations
        : [];
      renderInstallations();

      const candidates = await api(
        "/api/v1/onboarding/github/installations/candidates"
      );
      const candidateList = Array.isArray(candidates)
        ? candidates
        : [];

      renderCandidates(candidateList);
      updateOverview();

      if (state.installations.some(
        (item) => String(item.status || "").toUpperCase() === "ACTIVE"
      )) {
        window.clearInterval(githubPollTimer);
        showToast("GitHub connected.");
        return;
      }

      if (candidateList.length > 0) {
        window.clearInterval(githubPollTimer);
        showToast("Choose which verified GitHub installation to use.");
        return;
      }
    } catch {
      // The install may still be in progress. The normal workspace
      // refresh path will surface persistent API errors.
    }

    if (attempts >= 60) {
      window.clearInterval(githubPollTimer);
    }
  }, 2000);
}

async function loadCandidates() {
  const value = await api("/api/v1/onboarding/github/installations/candidates");
  renderCandidates(Array.isArray(value) ? value : []);
}

function renderCandidates(candidates) {
  const panel = byId("candidatePanel");
  const list = byId("candidateList");
  list.replaceChildren();

  if (!candidates.length) {
    panel.classList.add("hidden");
    return;
  }

  panel.classList.remove("hidden");
  candidates.forEach((candidate) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "button ghost";
    button.textContent = "Use installation";

    button.addEventListener("click", async () => {
      setButtonBusy(button, true, "Connecting…");
      try {
        await api(
          "/api/v1/onboarding/github/installations/" + candidate.installationId + "/claim",
          { method: "POST" }
        );
        showToast("Connected " + candidate.accountLogin + ".");
        panel.classList.add("hidden");
        await loadInstallations();
        updateOverview();
      } catch (error) {
        showToast(error.message || "Could not claim installation.", true);
      } finally {
        setButtonBusy(button, false);
      }
    });

    list.append(
      makeStackItem(
        candidate.accountLogin || "GitHub account",
        (candidate.accountType || "Account") + " · installation #" + candidate.installationId,
        button
      )
    );
  });
}

async function loadMappings() {
  const value = await api("/api/v1/catalog/repositories");
  state.mappings = Array.isArray(value) ? value : [];
  renderMappings();
  return state.mappings;
}

function parseRepository(value) {
  const match = String(value || "").trim().match(/^([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+)$/);
  if (!match) throw new Error("Repository must use owner/name format.");
  return { owner: match[1], repo: match[2] };
}

function renderMappings() {
  const tbody = byId("mappingRows");
  tbody.replaceChildren();

  if (!state.mappings.length) {
    const row = document.createElement("tr");
    const cell = document.createElement("td");
    cell.colSpan = 4;
    cell.className = "muted";
    cell.textContent = "No mappings yet.";
    row.append(cell);
    tbody.append(row);
    return;
  }

  state.mappings.forEach((mapping) => {
    const row = document.createElement("tr");
    const repoCell = document.createElement("td");
    repoCell.textContent = mapping.repository || "—";
    const serviceCell = document.createElement("td");
    serviceCell.textContent = mapping.service || "—";
    const updatedCell = document.createElement("td");
    updatedCell.textContent = formatDate(mapping.updatedAt);
    const actionCell = document.createElement("td");
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "text-button";
    remove.textContent = "Remove";

    remove.addEventListener("click", async () => {
      try {
        const parsed = parseRepository(mapping.repository);
        await api(
          "/api/v1/catalog/repositories/" +
            encodeURIComponent(parsed.owner) + "/" + encodeURIComponent(parsed.repo),
          { method: "DELETE" }
        );
        showToast("Removed " + mapping.repository + " mapping.");
        await loadMappings();
        updateOverview();
      } catch (error) {
        showToast(error.message || "Could not remove mapping.", true);
      }
    });

    actionCell.append(remove);
    row.append(repoCell, serviceCell, updatedCell, actionCell);
    tbody.append(row);
  });
}

async function saveMapping(event) {
  event.preventDefault();
  const submit = event.currentTarget.querySelector('button[type="submit"]');
  setButtonBusy(submit, true, "Saving…");

  try {
    const parsed = parseRepository(byId("repositoryInput").value);
    const service = byId("serviceInput").value.trim();
    if (!service) throw new Error("Runtime service is required.");

    await api(
      "/api/v1/catalog/repositories/" +
        encodeURIComponent(parsed.owner) + "/" + encodeURIComponent(parsed.repo),
      {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ service: service })
      }
    );

    byId("repositoryInput").value = "";
    byId("serviceInput").value = "";
    showToast("Mapped " + parsed.owner + "/" + parsed.repo + " to " + service + ".");
    await loadMappings();
    updateOverview();
  } catch (error) {
    showToast(error.message || "Could not save mapping.", true);
  } finally {
    setButtonBusy(submit, false);
  }
}

async function loadDependencies() {
  const value = await api("/api/v1/telemetry/dependencies");
  state.dependencies = Array.isArray(value) ? value : [];
  renderDependencies();
  return state.dependencies;
}

function renderDependencies() {
  const list = byId("dependencyList");
  list.replaceChildren();
  const services = new Set();
  let latest = null;

  state.dependencies.forEach((edge) => {
    if (edge.sourceService) services.add(edge.sourceService);
    if (edge.targetService) services.add(edge.targetService);

    if (edge.lastSeen) {
      const date = new Date(edge.lastSeen);
      if (!Number.isNaN(date.getTime()) && (!latest || date > latest)) latest = date;
    }
  });

  byId("serviceCount").textContent = String(services.size);
  byId("lastEdge").textContent = latest ? formatDate(latest.toISOString()) : "No data";

  if (!state.dependencies.length) {
    list.className = "stack-list compact-list empty-state";
    list.textContent = "No runtime dependency edges observed yet.";
    return;
  }

  list.className = "stack-list compact-list";
  state.dependencies.slice(0, 30).forEach((edge) => {
    const endpoint = edge.endpoint && edge.endpoint !== "*" ? " · " + edge.endpoint : "";
    const detail =
      "calls=" + (edge.callCount ?? 0) + endpoint + " · last seen " + formatDate(edge.lastSeen);
    list.append(
      makeStackItem(
        (edge.sourceService || "?") + " → " + (edge.targetService || "?"),
        detail
      )
    );
  });

  if (state.dependencies.length > 30) {
    const more = document.createElement("div");
    more.className = "muted small";
    more.textContent = "+" + (state.dependencies.length - 30) + " more edges";
    list.append(more);
  }
}

function renderTelemetrySnippet() {
  const token = state.ingestToken || "<ingest-token>";
  const tenant = state.tenant || "default";
  const endpoint = window.location.origin + "/api/v1/telemetry/otlp-json/v1/traces";

  byId("telemetrySnippet").textContent =
    "curl -X POST '" + endpoint + "' \\\n" +
    "  -H 'Authorization: Bearer " + token + "' \\\n" +
    "  -H 'X-Tenant-ID: " + tenant + "' \\\n" +
    "  -H 'Content-Type: application/json' \\\n" +
    "  --data-binary @otlp-traces.json";
}

function updateOverview() {
  const active = state.installations.filter(
    (item) => String(item.status || "").toUpperCase() === "ACTIVE"
  ).length;

  byId("githubMetric").textContent = active ? "Connected" : "Not connected";
  byId("githubMetricDetail").textContent = active
    ? active + " active installation" + (active === 1 ? "" : "s")
    : "Install the GitHub App";

  byId("repoMetric").textContent = String(state.mappings.length);
  byId("edgeMetric").textContent = String(state.dependencies.length);
  byId("edgeMetricDetail").textContent = state.dependencies.length
    ? "Observed service calls"
    : "Waiting for telemetry";

  const connected =
    (active ? 1 : 0) +
    (state.mappings.length ? 1 : 0) +
    (state.dependencies.length ? 1 : 0);

  byId("setupProgress").textContent = connected + " / 3 connected";
}

async function analyzePullRequest(event) {
  event.preventDefault();
  const submit = event.currentTarget.querySelector('button[type="submit"]');
  const loading = byId("analysisLoading");
  const resultPanel = byId("analysisResult");
  setButtonBusy(submit, true, "Analyzing…");
  loading.classList.remove("hidden");
  resultPanel.classList.add("hidden");

  try {
    const parsed = parseRepository(byId("analysisRepo").value);
    const number = Number(byId("analysisNumber").value);
    const service = byId("analysisService").value.trim();

    if (!Number.isInteger(number) || number < 1) {
      throw new Error("PR number must be a positive integer.");
    }

    const query = service ? "?service=" + encodeURIComponent(service) : "";
    const response = await api(
      "/api/v1/pr/" +
        encodeURIComponent(parsed.owner) + "/" +
        encodeURIComponent(parsed.repo) + "/" +
        number + "/impact" + query
    );

    renderAnalysis(response || {});
    resultPanel.classList.remove("hidden");
  } catch (error) {
    showToast(error.message || "PR analysis failed.", true);
  } finally {
    loading.classList.add("hidden");
    setButtonBusy(submit, false);
  }
}

function renderAnalysis(response) {
  const changes =
    response.changeSet && Array.isArray(response.changeSet.changes)
      ? response.changeSet.changes
      : [];
  const findings = Array.isArray(response.findings) ? response.findings : [];
  const coverage = Array.isArray(response.coverage) ? response.coverage : [];

  byId("changeCount").textContent = String(changes.length);
  byId("findingCount").textContent = String(findings.length);
  renderCoverage(coverage);
  renderFindings(findings);
  renderChanges(changes);
}

function renderCoverage(coverage) {
  const container = byId("coverageList");
  container.replaceChildren();

  if (!coverage.length) {
    const empty = document.createElement("p");
    empty.className = "muted";
    empty.textContent = "Coverage metadata unavailable.";
    container.append(empty);
    return;
  }

  coverage.forEach((item) => {
    const card = document.createElement("div");
    card.className = "coverage-card";
    const source = document.createElement("strong");
    source.textContent = item.source || "UNKNOWN_SOURCE";
    const status = document.createElement("span");
    const statusValue = String(item.status || "UNKNOWN");
    status.className = "coverage-status " + statusValue.toLowerCase();
    status.textContent = statusValue;
    const detail = document.createElement("p");
    detail.textContent = item.detail || "No coverage detail.";
    card.append(source, status, detail);
    container.append(card);
  });
}

function renderFindings(findings) {
  const container = byId("findingList");
  container.replaceChildren();

  if (!findings.length) {
    container.className = "stack-list empty-state";
    container.textContent =
      "No production evidence findings were returned. Check evidence coverage before interpreting this result.";
    return;
  }

  container.className = "stack-list";
  findings.forEach((finding) => {
    container.append(
      makeStackItem(
        (finding.confidence || "UNKNOWN") + " · " + (finding.component || "component"),
        (finding.relationship || "") + " — " + (finding.evidence || "")
      )
    );
  });
}

function renderChanges(changes) {
  const container = byId("changeList");
  container.replaceChildren();

  if (!changes.length) {
    container.className = "stack-list empty-state";
    container.textContent = "No supported changes detected.";
    return;
  }

  container.className = "stack-list";
  changes.forEach((change) => {
    const file = change.file ? " · " + change.file : "";
    container.append(
      makeStackItem(
        (change.operation || "CHANGED") + " " + (change.kind || "CHANGE"),
        (change.identifier || "") + file
      )
    );
  });
}

async function copyTelemetrySnippet() {
  try {
    await navigator.clipboard.writeText(byId("telemetrySnippet").textContent);
    showToast("Telemetry command copied.");
  } catch {
    showToast("Clipboard access is unavailable in this browser.", true);
  }
}

function activateNavigation() {
  const links = Array.from(document.querySelectorAll(".nav-link"));
  links.forEach((link) => {
    link.addEventListener("click", () => {
      links.forEach((other) => other.classList.remove("active"));
      link.classList.add("active");
    });
  });
}

function bindEvents() {
  byId("credentialForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    saveCredentials(
      byId("tenantInput").value,
      byId("apiTokenInput").value,
      byId("ingestTokenInput").value
    );
    byId("credentialGate").classList.add("hidden");
    await refreshAll();
  });

  byId("forgetButton").addEventListener("click", forgetCredentials);
  byId("refreshButton").addEventListener("click", () => refreshAll(true));
  byId("connectGitHubButton").addEventListener("click", connectGitHub);
  byId("mappingForm").addEventListener("submit", saveMapping);
  byId("analysisForm").addEventListener("submit", analyzePullRequest);
  byId("copyTelemetryButton").addEventListener("click", copyTelemetrySnippet);

  window.addEventListener("message", async (event) => {
    if (event.origin !== window.location.origin) return;
    const message = event.data;

    if (
      !message ||
      message.source !== "pr-blast-radius" ||
      message.type !== "github-install-complete"
    ) return;

    if (message.tenantId !== state.tenant) {
      showToast("GitHub callback tenant did not match this workspace.", true);
      return;
    }

    window.clearInterval(githubPollTimer);

    if (message.status === "select") {
      await loadCandidates();
      showToast("Choose which verified GitHub installation to use.");
    } else if (message.status === "connected") {
      await loadInstallations();
      renderCandidates([]);
      updateOverview();
      showToast("GitHub connected.");
    } else {
      showToast("No accessible GitHub installation was found.", true);
    }
  });

  activateNavigation();
}

async function initialize() {
  bindEvents();
  const hasSession = Boolean(state.tenant);

  byId("tenantInput").value = state.tenant || "default";
  byId("apiTokenInput").value = state.apiToken;
  byId("ingestTokenInput").value = state.ingestToken;
  byId("tenantBadge").textContent = state.tenant || "default";
  renderTelemetrySnippet();

  if (hasSession) {
    byId("credentialGate").classList.add("hidden");
    await refreshAll();
  }
}

document.addEventListener("DOMContentLoaded", initialize);
