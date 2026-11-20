const byId = (id) => document.getElementById(id);
const list = byId("report-list");
const status = byId("status");
const form = byId("connect");
const older = byId("more");
const reset = byId("demo");
const sample = {
  package_name: "org.example.travelnotes", score: 30, level: "moderate",
  evidence: [
    { permission: "android.permission.ACCESS_FINE_LOCATION", weight: 15, explanation: "May access precise device location if granted." },
    { permission: "android.permission.READ_CONTACTS", weight: 15, explanation: "May read address book entries if granted." },
  ],
};
let workspace = null;
let cursor = null;
let activeRequest = null;

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function render(report) {
  const article = element("article", "", "report");
  const top = element("div", "", "card-top");
  const level = ["low", "moderate", "high"].includes(report.level) ? report.level : "";
  top.append(element("h3", report.package_name), element("span", `${report.level} · ${report.score}/100`, `badge ${level}`));
  article.append(top);
  if (report.created_at) {
    article.append(element("p", new Date(report.created_at).toLocaleString(), "meta"));
  }
  const details = element("details");
  details.open = true;
  details.append(element("summary", `Permission findings (${report.evidence.length})`));
  const findings = element("ul");
  for (const item of report.evidence) {
    const finding = element("li");
    finding.append(element("strong", `${item.permission} · +${item.weight}`), element("div", item.explanation));
    findings.append(finding);
  }
  details.append(report.evidence.length ? findings : element("p", "No rules matched the declared permissions."));
  article.append(details);
  if (report.id) {
    article.append(element("p", `SHA-256 · ${report.sha256}`, "meta"));
    const remove = element("button", "Delete shared report");
    remove.type = "button";
    remove.addEventListener("click", async () => {
      if (!confirm(`Delete the shared report for ${report.package_name}?`)) return;
      const selected = workspace;
      remove.disabled = true;
      try {
        await request(selected, `/${encodeURIComponent(report.id)}`, { method: "DELETE" });
        if (workspace === selected) await load(selected);
      } catch (error) {
        if (workspace === selected) status.textContent = error.message;
      } finally {
        remove.disabled = false;
      }
    });
    article.append(remove);
  }
  list.append(article);
}

async function request(selection, suffix, options = {}) {
  const response = await fetch(`${selection.api}/v1/reports${suffix}`, {
    ...options,
    headers: { Authorization: `Bearer ${selection.key}` },
    cache: "no-store",
    redirect: "error",
  });
  if (!response.ok) throw new Error(`Request failed (${response.status}). Check your workspace settings.`);
  return response.status === 204 ? null : response.json();
}

async function load(selection, append = false) {
  activeRequest?.abort();
  const controller = new AbortController();
  activeRequest = controller;
  older.disabled = true;
  status.textContent = "Loading reports…";
  try {
    const rows = await request(selection, append ? `?before=${encodeURIComponent(cursor)}` : "", { signal: controller.signal });
    if (activeRequest !== controller) return;
    workspace = selection;
    byId("mode").textContent = "Private workspace";
    reset.hidden = false;
    if (!append) list.replaceChildren();
    rows.forEach(render);
    cursor = rows.at(-1)?.id ?? null;
    older.hidden = rows.length < 50;
    status.textContent = list.children.length ? "" : "No reports yet. Share an inspection from AppGuardian for Android.";
  } catch (error) {
    if (activeRequest === controller) status.textContent = error.message;
  } finally {
    if (activeRequest === controller) older.disabled = false;
  }
}

function showSample() {
  activeRequest?.abort();
  activeRequest = null;
  workspace = null;
  cursor = null;
  status.textContent = "";
  byId("mode").textContent = "Sample workspace";
  reset.hidden = older.hidden = true;
  byId("key").value = "";
  list.replaceChildren();
  render(sample);
}

reset.addEventListener("click", showSample);
older.addEventListener("click", () => load(workspace, true));
form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const url = new URL(byId("api").value);
  const local = ["localhost", "127.0.0.1"].includes(url.hostname);
  if ((url.protocol !== "https:" && !(local && url.protocol === "http:")) ||
      url.username || url.password || url.pathname !== "/" || url.search || url.hash) {
    status.textContent = "Enter an HTTPS API origin, or a local HTTP origin for development.";
    return;
  }
  const key = byId("key").value;
  byId("key").value = "";
  await load({ api: url.origin, key });
});
byId("generate").addEventListener("click", () => {
  const input = byId("key");
  input.value = Array.from(crypto.getRandomValues(new Uint8Array(32)), byte => byte.toString(16).padStart(2, "0")).join("");
  input.type = "text";
  input.select();
  status.textContent = "Copy the generated key somewhere private before connecting.";
});
byId("key").addEventListener("blur", () => { byId("key").type = "password"; });
showSample();
