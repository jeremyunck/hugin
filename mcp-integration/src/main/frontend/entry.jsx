import React, { useState, useEffect, useRef, useCallback } from "react";
import { createRoot } from "react-dom/client";
import {
  MessageSquare, Activity, Server, Wrench, Search, Send, Cpu,
  Database, Globe, RefreshCw, Zap, ChevronDown, Terminal,
  Clock, FolderTree, CheckCircle2, AlertTriangle, XCircle, Bird,
  LogOut, Lock, User, Eye, EyeOff, Trash2, X, Plus,
} from "lucide-react";

const C = {
  bg: "#080B11", panel: "#0F151E", panel2: "#141C28", panel3: "#1A2433",
  border: "#222D3D", borderLit: "#2E3C50", text: "#E7EEF6",
  mut: "#7A8699", mut2: "#566173",
  cyan: "#2BE7DA", cyanDim: "#1B8F89", gold: "#E9B44C",
  ok: "#4ADE80", warn: "#F4B740", err: "#FF5D5D",
};
const MONO = "'JetBrains Mono', ui-monospace, monospace";
const DISP = "'Chakra Petch', sans-serif";
const BODY = "'Sora', system-ui, sans-serif";

const MODELS = [
  { id: "openai/gpt-oss-120b", provider: "OpenRouter" },
  { id: "deepseek/deepseek-chat", provider: "OpenRouter" },
  { id: "llama3.2", provider: "Ollama" },
];

const STATUS = {
  up:        { c: C.ok,   label: "OPERATIONAL", Icon: CheckCircle2 },
  connected: { c: C.ok,   label: "CONNECTED",   Icon: CheckCircle2 },
  degraded:  { c: C.warn, label: "DEGRADED",    Icon: AlertTriangle },
  error:     { c: C.err,  label: "ERROR",        Icon: XCircle },
  down:      { c: C.err,  label: "OFFLINE",      Icon: XCircle },
};

const SERVICE_ICONS = { agent: Cpu, llm: Zap, memory: Database, search: Globe };

function serverStatus(s) {
  return s.connected ? "connected" : (s.error ? "error" : "down");
}

function serverIcon(name) {
  const n = (name || "").toLowerCase();
  if (n.includes("time")) return Clock;
  if (n.includes("search") || n.includes("web")) return Globe;
  if (n.includes("file") || n.includes("fs")) return FolderTree;
  return Server;
}

function serverCmd(def) {
  if (!def) return "";
  if (def.url) return def.url;
  return [def.command, ...(def.args || [])].filter(Boolean).join(" ");
}

// ── Primitives ─────────────────────────────────────────────────────────────────

function Dot({ color }) {
  return (
    <span style={{ position: "relative", display: "inline-flex", width: 8, height: 8 }}>
      <span style={{ position: "absolute", inset: 0, borderRadius: 99, background: color, opacity: 0.35, animation: "jpulse 2s ease-out infinite" }} />
      <span style={{ width: 8, height: 8, borderRadius: 99, background: color }} />
    </span>
  );
}

function Pill({ status }) {
  const s = STATUS[status] || STATUS.down;
  return (
    <span style={{
      display: "inline-flex", alignItems: "center", gap: 6, fontFamily: MONO, fontSize: 10,
      letterSpacing: "0.12em", color: s.c, background: `${s.c}14`, border: `1px solid ${s.c}33`,
      padding: "3px 8px", borderRadius: 5, fontWeight: 600,
    }}>
      <Dot color={s.c} /> {s.label}
    </span>
  );
}

function Panel({ children, style, pad = 18 }) {
  return (
    <div style={{ background: C.panel, border: `1px solid ${C.border}`, borderRadius: 12, padding: pad, ...style }}>
      {children}
    </div>
  );
}

function SectionLabel({ children, right }) {
  return (
    <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 14 }}>
      <span style={{ fontFamily: DISP, fontSize: 12, letterSpacing: "0.22em", color: C.mut, fontWeight: 600, textTransform: "uppercase" }}>{children}</span>
      {right}
    </div>
  );
}

function Bubble({ m }) {
  if (m.role === "tool") {
    return (
      <div style={{ alignSelf: "flex-start", maxWidth: "80%", background: `${C.gold}0E`, border: `1px solid ${C.gold}33`, borderRadius: 10, padding: "10px 12px", fontFamily: MONO, fontSize: 12 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 7, color: C.gold, fontWeight: 600, marginBottom: 5 }}>
          <Wrench size={13} /> tool call · {m.tool}
        </div>
        <div style={{ color: C.mut }}>{m.args}</div>
      </div>
    );
  }
  const isUser = m.role === "user";
  return (
    <div style={{ alignSelf: isUser ? "flex-end" : "flex-start", maxWidth: "78%" }}>
      {!isUser && (
        <div style={{ display: "flex", alignItems: "center", gap: 6, marginBottom: 5 }}>
          <span style={{ width: 6, height: 6, borderRadius: 99, background: C.cyan, boxShadow: `0 0 6px ${C.cyan}` }} />
          <span style={{ fontFamily: DISP, fontSize: 11, letterSpacing: "0.18em", color: C.cyanDim, fontWeight: 600 }}>HUGIN</span>
        </div>
      )}
      <div style={{
        background: isUser ? C.cyan : C.panel2, color: isUser ? C.bg : C.text,
        border: isUser ? "none" : `1px solid ${C.border}`,
        borderRadius: 12, padding: "11px 15px", fontFamily: BODY, fontSize: 14, lineHeight: 1.55,
        whiteSpace: "pre-wrap",
      }}>
        {m.text}
      </div>
    </div>
  );
}

// ── Chat ───────────────────────────────────────────────────────────────────────

function Chat({ model, token, sessionId, onUnauth }) {
  const [msgs, setMsgs] = useState([
    { id: "seed", role: "assistant", text: "Systems online. Hugin ready. What do you need?" },
  ]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const endRef = useRef(null);
  const abortRef = useRef(null);

  useEffect(() => { endRef.current?.scrollIntoView({ behavior: "smooth" }); }, [msgs]);

  useEffect(() => { return () => { abortRef.current?.abort(); }; }, []);

  const send = async () => {
    if (!input.trim() || busy) return;
    const userText = input.trim();
    setMsgs(m => [...m, { role: "user", text: userText }]);
    setInput("");
    setBusy(true);

    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    let currentAssistantId = null;

    try {
      const res = await fetch("/api/agent/stream", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${token}`,
        },
        body: JSON.stringify({ prompt: userText, model, sessionId }),
        signal: controller.signal,
      });

      if (res.status === 401) { onUnauth?.(); return; }

      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        setMsgs(m => [...m, { role: "assistant", text: `Error: ${err.error || res.statusText}` }]);
        return;
      }

      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buf = "";

      outer: while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });

        const blocks = buf.split("\n\n");
        buf = blocks.pop() ?? "";

        for (const block of blocks) {
          if (!block.trim()) continue;
          let evtType = "";
          const dataLines = [];
          for (const line of block.split("\n")) {
            if (line.startsWith("event:")) evtType = line.slice(6).trim();
            else if (line.startsWith("data:")) dataLines.push(line.slice(5));
          }
          const dataStr = dataLines.join("\n").trim();
          if (!evtType) evtType = "message";

          let data = {};
          try { data = dataStr ? JSON.parse(dataStr) : {}; } catch {}

          if (evtType === "token") {
            if (currentAssistantId === null) {
              const id = `a-${Date.now()}-${Math.random().toString(36).slice(2)}`;
              currentAssistantId = id;
              setMsgs(m => [...m, { id, role: "assistant", text: data.text ?? "" }]);
            } else {
              const id = currentAssistantId;
              setMsgs(m => m.map(msg =>
                msg.id === id ? { ...msg, text: msg.text + (data.text ?? "") } : msg
              ));
            }
          } else if (evtType === "tool") {
            currentAssistantId = null;
            setMsgs(m => [...m, { role: "tool", tool: data.name, args: data.args ?? "" }]);
          } else if (evtType === "done") {
            break outer;
          } else if (evtType === "error") {
            setMsgs(m => [...m, { role: "assistant", text: `Error: ${data.message}` }]);
            break outer;
          }
        }
      }
    } catch (e) {
      if (e.name === "AbortError") return;
      setMsgs(m => [...m, { role: "assistant", text: `Connection error: ${e.message}` }]);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100%" }}>
      <div style={{ flex: 1, overflowY: "auto", padding: "4px 4px 8px", display: "flex", flexDirection: "column", gap: 16 }}>
        {msgs.map((m, i) => <Bubble key={m.id ?? i} m={m} />)}
        {busy && <span style={{ fontFamily: MONO, fontSize: 11, color: C.cyanDim, letterSpacing: "0.1em" }}>▌ generating…</span>}
        <div ref={endRef} />
      </div>
      <div style={{ marginTop: 14, display: "flex", gap: 10, alignItems: "flex-end" }}>
        <div style={{ flex: 1, background: C.panel2, border: `1px solid ${C.borderLit}`, borderRadius: 12, padding: "12px 14px", display: "flex", alignItems: "center", gap: 10 }}>
          <Terminal size={16} color={C.cyanDim} />
          <input
            value={input} onChange={e => setInput(e.target.value)}
            onKeyDown={e => e.key === "Enter" && send()}
            placeholder={`Message Hugin  ·  ${model}`}
            style={{ flex: 1, background: "transparent", border: "none", outline: "none", color: C.text, fontFamily: BODY, fontSize: 14 }}
          />
        </div>
        <button onClick={send} disabled={busy} style={{
          background: busy ? C.panel3 : C.cyan, color: busy ? C.mut : C.bg, border: "none",
          borderRadius: 12, width: 48, height: 48, display: "grid", placeItems: "center",
          cursor: busy ? "default" : "pointer", transition: "all .15s",
          boxShadow: busy ? "none" : `0 0 18px ${C.cyan}55`,
        }}>
          <Send size={18} />
        </button>
      </div>
      <div style={{ fontFamily: MONO, fontSize: 10, color: C.mut2, marginTop: 8, letterSpacing: "0.06em" }}>
        POST /api/agent/stream · SSE · session {sessionId?.slice(0, 8)}…
      </div>
    </div>
  );
}

// ── Services ───────────────────────────────────────────────────────────────────

function ServiceRow({ s, compact }) {
  const Icon = s.icon || SERVICE_ICONS[s.key] || Server;
  const st = STATUS[s.status] || STATUS.down;
  return (
    <div style={{
      display: "flex", alignItems: "center", gap: 12, padding: compact ? "10px 0" : "14px 16px",
      borderBottom: compact ? `1px solid ${C.border}` : "none",
      background: compact ? "transparent" : C.panel2, borderRadius: compact ? 0 : 10,
      border: compact ? "none" : `1px solid ${C.border}`, marginBottom: compact ? 0 : 10,
    }}>
      <div style={{ width: 34, height: 34, borderRadius: 8, background: `${st.c}14`, border: `1px solid ${st.c}30`, display: "grid", placeItems: "center", flexShrink: 0 }}>
        <Icon size={16} color={st.c} />
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontFamily: BODY, fontSize: 13.5, fontWeight: 600, color: C.text }}>{s.name}</div>
        <div style={{ fontFamily: MONO, fontSize: 10.5, color: C.mut, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{s.detail}</div>
      </div>
      <div style={{ textAlign: "right", flexShrink: 0 }}>
        <Dot color={st.c} />
        {!compact && s.meta && <div style={{ fontFamily: MONO, fontSize: 10, color: C.mut2, marginTop: 6 }}>{s.meta}</div>}
      </div>
    </div>
  );
}

function Stat({ label, value, accent }) {
  return (
    <Panel pad={16} style={{ position: "relative", overflow: "hidden" }}>
      <div style={{ position: "absolute", top: 0, left: 0, width: 3, height: "100%", background: accent }} />
      <div style={{ fontFamily: DISP, fontSize: 11, letterSpacing: "0.18em", color: C.mut, textTransform: "uppercase" }}>{label}</div>
      <div style={{ fontFamily: DISP, fontSize: 34, fontWeight: 700, color: C.text, marginTop: 6, lineHeight: 1 }}>{value}</div>
    </Panel>
  );
}

function ServicesView({ services, servers }) {
  const connectedCount = servers.filter(s => s.connected).length;
  const totalTools = servers.reduce((n, s) => n + (s.tools?.length ?? 0), 0);
  const upCount = services.filter(s => s.status === "up").length;
  return (
    <div>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(3,1fr)", gap: 14, marginBottom: 20 }}>
        <Stat label="Services Online" value={`${upCount}/${services.length || 4}`} accent={C.ok} />
        <Stat label="MCP Servers" value={`${connectedCount}/${servers.length}`} accent={C.cyan} />
        <Stat label="Tools Available" value={totalTools} accent={C.gold} />
      </div>
      <Panel>
        <SectionLabel>System Vitals</SectionLabel>
        {services.length > 0
          ? services.map(s => <ServiceRow key={s.key} s={s} />)
          : <div style={{ fontFamily: MONO, fontSize: 11, color: C.mut, textAlign: "center", padding: 24 }}>Loading…</div>}
      </Panel>
    </div>
  );
}

// ── Add Server Modal ───────────────────────────────────────────────────────────

function AddServerModal({ token, onSuccess, onClose }) {
  const [name, setName] = useState("");
  const [type, setType] = useState("stdio");
  const [command, setCommand] = useState("");
  const [url, setUrl] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const submit = async e => {
    e.preventDefault();
    if (!name.trim()) return;
    setLoading(true);
    setError("");
    try {
      const parts = command.trim().split(/\s+/);
      const def = type === "sse"
        ? { url: url.trim() }
        : { command: parts[0], args: parts.slice(1) };

      const res = await fetch(`/api/servers/${encodeURIComponent(name.trim())}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json", "Authorization": `Bearer ${token}` },
        body: JSON.stringify(def),
      });
      if (res.ok) {
        onSuccess();
      } else {
        const err = await res.json().catch(() => ({}));
        setError(err.error || "Failed to add server");
      }
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  const fieldStyle = {
    background: C.panel2, border: `1px solid ${C.borderLit}`, borderRadius: 8,
    padding: "10px 12px", fontFamily: MONO, fontSize: 12, color: C.text,
    width: "100%", boxSizing: "border-box", outline: "none",
  };
  const labelStyle = {
    fontFamily: DISP, fontSize: 10, letterSpacing: "0.18em", color: C.mut,
    textTransform: "uppercase", display: "block", marginBottom: 6,
  };

  return (
    <div style={{ position: "fixed", inset: 0, background: "#000000aa", zIndex: 100, display: "flex", alignItems: "center", justifyContent: "center" }}>
      <form onSubmit={submit} style={{
        background: C.panel, border: `1px solid ${C.borderLit}`, borderRadius: 16,
        padding: "32px 36px", width: 440, boxShadow: "0 20px 60px #000c", animation: "jfade .25s ease",
      }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 24 }}>
          <span style={{ fontFamily: DISP, fontSize: 15, fontWeight: 700, letterSpacing: "0.18em", color: C.text }}>ADD MCP SERVER</span>
          <button type="button" onClick={onClose} style={{ background: "none", border: "none", cursor: "pointer", color: C.mut, padding: 0 }}><X size={18} /></button>
        </div>

        <div style={{ marginBottom: 14 }}>
          <label style={labelStyle}>Server Name</label>
          <input value={name} onChange={e => setName(e.target.value)} placeholder="e.g. filesystem" style={fieldStyle} />
        </div>

        <div style={{ marginBottom: 14 }}>
          <label style={labelStyle}>Transport</label>
          <div style={{ display: "flex", gap: 10 }}>
            {["stdio", "sse"].map(t => (
              <button key={t} type="button" onClick={() => setType(t)} style={{
                flex: 1, padding: "9px 0", borderRadius: 7,
                border: `1px solid ${type === t ? C.cyan : C.borderLit}`,
                background: type === t ? `${C.cyan}14` : C.panel3,
                color: type === t ? C.cyan : C.mut,
                fontFamily: MONO, fontSize: 12, cursor: "pointer", textTransform: "uppercase", letterSpacing: "0.1em",
              }}>{t}</button>
            ))}
          </div>
        </div>

        {type === "stdio" ? (
          <div style={{ marginBottom: 14 }}>
            <label style={labelStyle}>Command + Args</label>
            <input value={command} onChange={e => setCommand(e.target.value)}
              placeholder="uvx mcp-server-time" style={fieldStyle} />
          </div>
        ) : (
          <div style={{ marginBottom: 14 }}>
            <label style={labelStyle}>URL</label>
            <input value={url} onChange={e => setUrl(e.target.value)}
              placeholder="https://example.com/sse" style={fieldStyle} />
          </div>
        )}

        {error && (
          <div style={{ fontFamily: MONO, fontSize: 11, color: C.err, background: `${C.err}12`, border: `1px solid ${C.err}33`, borderRadius: 7, padding: "8px 10px", marginBottom: 14, display: "flex", alignItems: "center", gap: 7 }}>
            <XCircle size={12} /> {error}
          </div>
        )}

        <div style={{ display: "flex", gap: 10, justifyContent: "flex-end", marginTop: 20 }}>
          <button type="button" onClick={onClose} style={{
            padding: "9px 20px", borderRadius: 8, border: `1px solid ${C.borderLit}`,
            background: "transparent", color: C.mut, fontFamily: MONO, fontSize: 12, cursor: "pointer",
          }}>Cancel</button>
          <button type="submit" disabled={loading || !name.trim()} style={{
            padding: "9px 20px", borderRadius: 8, border: "none",
            background: loading || !name.trim() ? C.panel3 : C.cyan,
            color: loading || !name.trim() ? C.mut : C.bg,
            fontFamily: DISP, fontSize: 12, fontWeight: 700, letterSpacing: "0.14em",
            cursor: loading || !name.trim() ? "default" : "pointer",
          }}>
            {loading ? "ADDING…" : "ADD SERVER"}
          </button>
        </div>
      </form>
    </div>
  );
}

// ── Servers View ───────────────────────────────────────────────────────────────

function ServersView({ servers, token, onRefresh, onUnauth }) {
  const [spin, setSpin] = useState(null);
  const [deleting, setDeleting] = useState(null);
  const [showAdd, setShowAdd] = useState(false);

  const reconnect = async name => {
    setSpin(name);
    try {
      const res = await fetch(`/api/servers/${encodeURIComponent(name)}/reconnect`, {
        method: "POST", headers: { "Authorization": `Bearer ${token}` },
      });
      if (res.status === 401) { onUnauth?.(); return; }
      await onRefresh();
    } catch {} finally { setSpin(null); }
  };

  const removeServer = async name => {
    if (!confirm(`Remove server "${name}"?`)) return;
    setDeleting(name);
    try {
      const res = await fetch(`/api/servers/${encodeURIComponent(name)}`, {
        method: "DELETE", headers: { "Authorization": `Bearer ${token}` },
      });
      if (res.status === 401) { onUnauth?.(); return; }
      await onRefresh();
    } catch {} finally { setDeleting(null); }
  };

  return (
    <>
      {showAdd && (
        <AddServerModal token={token} onSuccess={() => { setShowAdd(false); onRefresh(); }} onClose={() => setShowAdd(false)} />
      )}
      <div style={{ display: "grid", gridTemplateColumns: "repeat(2,1fr)", gap: 14 }}>
        {servers.map(s => {
          const status = serverStatus(s);
          const st = STATUS[status] || STATUS.down;
          const Icon = serverIcon(s.name);
          const tools = s.tools ?? [];
          const transport = s.definition?.url ? "sse" : "stdio";
          return (
            <Panel key={s.name} pad={16}>
              <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between" }}>
                <div style={{ display: "flex", gap: 11, alignItems: "center" }}>
                  <div style={{ width: 38, height: 38, borderRadius: 9, background: `${st.c}12`, border: `1px solid ${st.c}30`, display: "grid", placeItems: "center" }}>
                    <Icon size={18} color={st.c} />
                  </div>
                  <div>
                    <div style={{ fontFamily: DISP, fontSize: 16, fontWeight: 600, color: C.text }}>{s.name}</div>
                    <span style={{ fontFamily: MONO, fontSize: 9.5, letterSpacing: "0.1em", color: C.mut, textTransform: "uppercase", background: C.panel3, padding: "2px 6px", borderRadius: 4 }}>{transport}</span>
                  </div>
                </div>
                <Pill status={status} />
              </div>
              <div style={{ fontFamily: MONO, fontSize: 11, color: C.mut, marginTop: 12, background: C.bg, border: `1px solid ${C.border}`, borderRadius: 7, padding: "8px 10px", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
                $ {serverCmd(s.definition)}
              </div>
              {s.error && (
                <div style={{ fontFamily: MONO, fontSize: 10.5, color: C.err, marginTop: 6, background: `${C.err}0a`, borderRadius: 6, padding: "5px 8px" }}>{s.error}</div>
              )}
              <div style={{ marginTop: 12, display: "flex", flexWrap: "wrap", gap: 6, minHeight: 24 }}>
                {tools.length === 0
                  ? <span style={{ fontFamily: MONO, fontSize: 11, color: s.connected ? C.mut : C.err }}>
                      {s.connected ? "no tools discovered" : "no tools — connection failed"}
                    </span>
                  : tools.map(t => (
                    <span key={t.name} style={{ fontFamily: MONO, fontSize: 10.5, color: C.cyan, background: `${C.cyan}12`, border: `1px solid ${C.cyan}28`, padding: "3px 8px", borderRadius: 5 }}>{t.name}</span>
                  ))}
              </div>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginTop: 14 }}>
                <span style={{ fontFamily: MONO, fontSize: 10.5, color: C.mut2 }}>{tools.length} tool{tools.length !== 1 ? "s" : ""}</span>
                <div style={{ display: "flex", gap: 8 }}>
                  <button onClick={() => removeServer(s.name)} disabled={deleting === s.name} style={{
                    display: "flex", alignItems: "center", gap: 5, background: "transparent",
                    color: C.err, border: `1px solid ${C.err}44`, borderRadius: 7,
                    padding: "6px 10px", fontFamily: MONO, fontSize: 11,
                    cursor: deleting === s.name ? "default" : "pointer",
                    opacity: deleting === s.name ? 0.5 : 1,
                  }}>
                    <Trash2 size={12} /> Remove
                  </button>
                  <button onClick={() => reconnect(s.name)} disabled={spin === s.name} style={{
                    display: "flex", alignItems: "center", gap: 6, background: C.panel3, color: C.text,
                    border: `1px solid ${C.borderLit}`, borderRadius: 7, padding: "6px 11px",
                    fontFamily: MONO, fontSize: 11, cursor: spin === s.name ? "default" : "pointer",
                  }}>
                    <RefreshCw size={12} style={{ animation: spin === s.name ? "jspin 1s linear infinite" : "none" }} />
                    Reconnect
                  </button>
                </div>
              </div>
            </Panel>
          );
        })}
        {servers.length === 0 && (
          <div style={{ gridColumn: "1 / -1", textAlign: "center", color: C.mut, fontFamily: MONO, fontSize: 12, padding: 40 }}>
            No MCP servers configured.
          </div>
        )}
        <button onClick={() => setShowAdd(true)} style={{
          gridColumn: "1 / -1", background: "transparent", border: `1.5px dashed ${C.borderLit}`,
          borderRadius: 12, padding: 16, color: C.mut, fontFamily: DISP, fontSize: 13, letterSpacing: "0.1em",
          cursor: "pointer", display: "flex", alignItems: "center", justifyContent: "center", gap: 8,
        }}>
          <Plus size={16} /> ADD MCP SERVER
        </button>
      </div>
    </>
  );
}

// ── Tools View ────────────────────────────────────────────────────────────────

function ToolsView({ tools }) {
  const [q, setQ] = useState("");
  const list = tools.filter(t =>
    t.name.toLowerCase().includes(q.toLowerCase()) ||
    t.server.toLowerCase().includes(q.toLowerCase()) ||
    (t.description || "").toLowerCase().includes(q.toLowerCase())
  );
  return (
    <div>
      <div style={{ display: "flex", alignItems: "center", gap: 10, background: C.panel2, border: `1px solid ${C.borderLit}`, borderRadius: 10, padding: "10px 14px", marginBottom: 16 }}>
        <Search size={16} color={C.mut} />
        <input value={q} onChange={e => setQ(e.target.value)} placeholder="Filter tools…"
          style={{ flex: 1, background: "transparent", border: "none", outline: "none", color: C.text, fontFamily: BODY, fontSize: 14 }} />
        <span style={{ fontFamily: MONO, fontSize: 11, color: C.mut2 }}>{list.length} of {tools.length}</span>
      </div>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(3,1fr)", gap: 12 }}>
        {list.map((t, i) => (
          <Panel key={i} pad={14} style={{ display: "flex", alignItems: "flex-start", gap: 11 }}>
            <div style={{ width: 32, height: 32, borderRadius: 8, background: `${C.cyan}12`, border: `1px solid ${C.cyan}28`, display: "grid", placeItems: "center", flexShrink: 0 }}>
              <Wrench size={14} color={C.cyan} />
            </div>
            <div style={{ minWidth: 0 }}>
              <div style={{ fontFamily: MONO, fontSize: 12.5, color: C.text, fontWeight: 600, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{t.name}</div>
              <div style={{ fontFamily: MONO, fontSize: 10, color: C.mut, marginTop: 2 }}>{t.server} · {t.transport}</div>
              {t.description && (
                <div style={{ fontFamily: BODY, fontSize: 11, color: C.mut2, marginTop: 3, display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflow: "hidden" }}>{t.description}</div>
              )}
            </div>
          </Panel>
        ))}
        {list.length === 0 && (
          <div style={{ gridColumn: "1 / -1", textAlign: "center", color: C.mut, fontFamily: MONO, fontSize: 12, padding: 40 }}>
            {tools.length === 0 ? "No tools available — connect MCP servers first." : `No tools match "${q}".`}
          </div>
        )}
      </div>
    </div>
  );
}

const NAV = [
  { key: "chat",    label: "Console", Icon: MessageSquare },
  { key: "status",  label: "Status",  Icon: Activity },
  { key: "servers", label: "Servers", Icon: Server },
  { key: "tools",   label: "Tools",   Icon: Wrench },
];

// ── Login Screen ──────────────────────────────────────────────────────────────

function LoginScreen({ onLogin }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showPw, setShowPw] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const submit = async e => {
    e?.preventDefault();
    if (!username.trim() || !password) return;
    setLoading(true);
    setError("");
    try {
      const res = await fetch("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: username.trim(), password }),
      });
      const data = await res.json();
      if (res.ok) {
        onLogin(data.token, data.username);
      } else {
        setError("Invalid username or password.");
      }
    } catch {
      setError("Connection error — is the server running?");
    } finally {
      setLoading(false);
    }
  };

  const fieldStyle = { display: "flex", alignItems: "center", gap: 10, background: C.panel2, border: `1px solid ${C.borderLit}`, borderRadius: 10, padding: "13px 16px" };
  const inputStyle = { flex: 1, background: "transparent", border: "none", outline: "none", color: C.text, fontFamily: BODY, fontSize: 14 };

  return (
    <div style={{ height: "100vh", background: C.bg, display: "flex", alignItems: "center", justifyContent: "center", fontFamily: BODY }}>
      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=Chakra+Petch:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600&family=Sora:wght@400;500;600&display=swap');
        @keyframes jpulse { 0%{transform:scale(1);opacity:.5} 100%{transform:scale(2.6);opacity:0} }
        @keyframes jfade  { from{opacity:0;transform:translateY(14px)} to{opacity:1;transform:none} }
        @keyframes jglow  { 0%,100%{opacity:.6} 50%{opacity:1} }
        *::-webkit-scrollbar { width:8px; height:8px }
        *::-webkit-scrollbar-thumb { background:#222D3D; border-radius:99px }
        *::-webkit-scrollbar-track { background:transparent }
      `}</style>
      <div style={{ position: "fixed", inset: 0, pointerEvents: "none", background: `radial-gradient(ellipse 60% 50% at 50% 45%, ${C.cyan}0D 0%, transparent 70%)` }} />
      <form onSubmit={submit} style={{
        position: "relative", width: 420, background: C.panel, border: `1px solid ${C.border}`,
        borderRadius: 20, padding: "44px 40px 40px",
        boxShadow: `0 0 80px ${C.cyan}0D, 0 24px 64px #00000060`, animation: "jfade .35s ease",
      }}>
        <div style={{ display: "flex", flexDirection: "column", alignItems: "center", marginBottom: 36 }}>
          <div style={{ width: 60, height: 60, borderRadius: 16, marginBottom: 18, display: "grid", placeItems: "center", background: `radial-gradient(circle, ${C.cyan}22, transparent 70%)`, border: `1px solid ${C.cyan}44`, boxShadow: `0 0 28px ${C.cyan}33` }}>
            <Bird size={28} color={C.cyan} style={{ filter: `drop-shadow(0 0 8px ${C.cyan}bb)`, animation: "jglow 3s ease-in-out infinite" }} />
          </div>
          <div style={{ fontFamily: DISP, fontSize: 24, fontWeight: 700, letterSpacing: "0.22em", color: C.text }}>HUGIN</div>
          <div style={{ fontFamily: MONO, fontSize: 10, letterSpacing: "0.28em", color: C.mut, marginTop: 5 }}>MCP CONTROL · SIGN IN</div>
        </div>

        <div style={{ marginBottom: 12 }}>
          <div style={{ fontFamily: DISP, fontSize: 10, letterSpacing: "0.2em", color: C.mut, textTransform: "uppercase", marginBottom: 7 }}>Username</div>
          <div style={fieldStyle}>
            <User size={15} color={C.cyanDim} />
            <input value={username} onChange={e => setUsername(e.target.value)} placeholder="admin" autoComplete="username" style={inputStyle} />
          </div>
        </div>

        <div style={{ marginBottom: 24 }}>
          <div style={{ fontFamily: DISP, fontSize: 10, letterSpacing: "0.2em", color: C.mut, textTransform: "uppercase", marginBottom: 7 }}>Password</div>
          <div style={fieldStyle}>
            <Lock size={15} color={C.cyanDim} />
            <input type={showPw ? "text" : "password"} value={password} onChange={e => setPassword(e.target.value)} placeholder="••••••••" autoComplete="current-password" style={inputStyle} />
            <button type="button" onClick={() => setShowPw(v => !v)} style={{ background: "none", border: "none", cursor: "pointer", padding: 0, color: C.mut, display: "grid", placeItems: "center" }}>
              {showPw ? <EyeOff size={15} /> : <Eye size={15} />}
            </button>
          </div>
        </div>

        {error && (
          <div style={{ fontFamily: MONO, fontSize: 11.5, color: C.err, background: `${C.err}12`, border: `1px solid ${C.err}33`, borderRadius: 8, padding: "9px 12px", marginBottom: 16, display: "flex", alignItems: "center", gap: 8 }}>
            <XCircle size={13} /> {error}
          </div>
        )}

        <button type="submit" disabled={loading || !username.trim() || !password} style={{
          width: "100%", padding: "14px 0", borderRadius: 10, border: "none",
          background: loading || !username.trim() || !password ? C.panel3 : C.cyan,
          color: loading || !username.trim() || !password ? C.mut : C.bg,
          fontFamily: DISP, fontSize: 13, fontWeight: 700, letterSpacing: "0.18em",
          cursor: loading || !username.trim() || !password ? "default" : "pointer", transition: "all .15s",
          boxShadow: loading || !username.trim() || !password ? "none" : `0 0 22px ${C.cyan}44`,
        }}>
          {loading ? "AUTHENTICATING…" : "SIGN IN"}
        </button>
        <div style={{ fontFamily: MONO, fontSize: 10, color: C.mut2, textAlign: "center", marginTop: 20, letterSpacing: "0.06em" }}>POST /api/auth/login · JWT Bearer</div>
      </form>
    </div>
  );
}

// ── App ────────────────────────────────────────────────────────────────────────

function App() {
  const [token, setToken] = useState(() => localStorage.getItem("hugin_token"));
  const [authUser, setAuthUser] = useState(() => localStorage.getItem("hugin_username") || "");
  const [view, setView] = useState("chat");
  const [model, setModel] = useState(MODELS[0].id);
  const [modelOpen, setModelOpen] = useState(false);
  const [servers, setServers] = useState([]);
  const [services, setServices] = useState([]);

  const sessionId = useRef(
    typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
      ? crypto.randomUUID()
      : Math.random().toString(36).slice(2) + Math.random().toString(36).slice(2)
  ).current;

  const handleLogout = useCallback(() => {
    localStorage.removeItem("hugin_token");
    localStorage.removeItem("hugin_username");
    setToken(null);
    setAuthUser("");
    setServers([]);
    setServices([]);
  }, []);

  const fetchServers = useCallback(async (tok) => {
    const t = tok ?? token;
    if (!t) return;
    try {
      const res = await fetch("/api/servers", { headers: { "Authorization": `Bearer ${t}` } });
      if (res.status === 401) { handleLogout(); return; }
      if (res.ok) setServers(await res.json());
    } catch (e) { console.error("fetchServers failed:", e); }
  }, [token, handleLogout]);

  const fetchStatus = useCallback(async (tok) => {
    const t = tok ?? token;
    if (!t) return;
    try {
      const res = await fetch("/api/status", { headers: { "Authorization": `Bearer ${t}` } });
      if (res.status === 401) { handleLogout(); return; }
      if (res.ok) setServices(await res.json());
    } catch (e) { console.error("fetchStatus failed:", e); }
  }, [token, handleLogout]);

  useEffect(() => {
    if (token) {
      fetchServers();
      fetchStatus();
    }
  }, [token]);

  const handleLogin = (tok, user) => {
    localStorage.setItem("hugin_token", tok);
    localStorage.setItem("hugin_username", user);
    setToken(tok);
    setAuthUser(user);
  };

  if (!token) return <LoginScreen onLogin={handleLogin} />;

  const allTools = servers.flatMap(s =>
    (s.tools ?? []).map(t => ({
      name: t.name,
      description: t.description,
      server: s.name,
      transport: s.definition?.url ? "sse" : "stdio",
    }))
  );

  return (
    <div style={{ display: "flex", height: "100vh", background: C.bg, color: C.text, fontFamily: BODY, overflow: "hidden" }}>
      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=Chakra+Petch:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600&family=Sora:wght@400;500;600&display=swap');
        @keyframes jpulse { 0%{transform:scale(1);opacity:.5} 100%{transform:scale(2.6);opacity:0} }
        @keyframes jspin  { to { transform: rotate(360deg) } }
        @keyframes jfade  { from{opacity:0;transform:translateY(8px)} to{opacity:1;transform:none} }
        *::-webkit-scrollbar { width:8px; height:8px }
        *::-webkit-scrollbar-thumb { background:#222D3D; border-radius:99px }
        *::-webkit-scrollbar-track { background:transparent }
      `}</style>

      {/* Sidebar */}
      <aside style={{ width: 92, background: C.panel, borderRight: `1px solid ${C.border}`, display: "flex", flexDirection: "column", alignItems: "center", paddingTop: 22, gap: 6, flexShrink: 0 }}>
        <div style={{ width: 44, height: 44, borderRadius: 12, display: "grid", placeItems: "center", marginBottom: 18, background: `radial-gradient(circle, ${C.cyan}2a, transparent 70%)`, border: `1px solid ${C.cyan}40` }}>
          <Bird size={22} color={C.cyan} style={{ filter: `drop-shadow(0 0 6px ${C.cyan}aa)` }} />
        </div>
        {NAV.map(({ key, label, Icon }) => {
          const on = view === key;
          return (
            <button key={key} onClick={() => setView(key)} style={{
              width: 64, padding: "11px 0", borderRadius: 10, border: "none", cursor: "pointer",
              background: on ? `${C.cyan}14` : "transparent", color: on ? C.cyan : C.mut,
              display: "flex", flexDirection: "column", alignItems: "center", gap: 6, transition: "all .15s",
            }}>
              <Icon size={20} />
              <span style={{ fontFamily: DISP, fontSize: 10, letterSpacing: "0.1em", fontWeight: 600 }}>{label}</span>
            </button>
          );
        })}
        <div style={{ marginTop: "auto", marginBottom: 18 }}>
          <button onClick={handleLogout} title="Sign out" style={{ background: "none", border: "none", cursor: "pointer", color: C.mut2, padding: 4 }}>
            <LogOut size={18} />
          </button>
        </div>
      </aside>

      <div style={{ flex: 1, display: "flex", flexDirection: "column", minWidth: 0 }}>
        {/* Header */}
        <header style={{ height: 64, borderBottom: `1px solid ${C.border}`, display: "flex", alignItems: "center", justifyContent: "space-between", padding: "0 26px", flexShrink: 0 }}>
          <div>
            <div style={{ fontFamily: DISP, fontSize: 19, fontWeight: 700, letterSpacing: "0.16em", color: C.text }}>
              HUGIN <span style={{ color: C.cyan }}>·</span> <span style={{ color: C.mut, fontSize: 13, letterSpacing: "0.2em" }}>MCP CONTROL</span>
            </div>
            <div style={{ fontFamily: MONO, fontSize: 10, color: C.mut2, letterSpacing: "0.08em" }}>mcp-client · spring-boot agent · :8080</div>
          </div>
          <div style={{ display: "flex", alignItems: "center", gap: 14 }}>
            {/* Model selector */}
            <div style={{ position: "relative" }}>
              <button onClick={() => setModelOpen(o => !o)} style={{
                display: "flex", alignItems: "center", gap: 9, background: C.panel2, border: `1px solid ${C.borderLit}`,
                borderRadius: 9, padding: "8px 13px", color: C.text, cursor: "pointer", fontFamily: MONO, fontSize: 12,
              }}>
                <Zap size={13} color={C.gold} /> {model} <ChevronDown size={13} color={C.mut} />
              </button>
              {modelOpen && (
                <div style={{ position: "absolute", top: 44, right: 0, background: C.panel2, border: `1px solid ${C.borderLit}`, borderRadius: 10, padding: 6, minWidth: 230, zIndex: 20, boxShadow: "0 12px 30px #000a" }}>
                  {MODELS.map(m => (
                    <button key={m.id} onClick={() => { setModel(m.id); setModelOpen(false); }} style={{
                      display: "flex", justifyContent: "space-between", width: "100%",
                      background: model === m.id ? `${C.cyan}12` : "transparent",
                      border: "none", borderRadius: 7, padding: "9px 11px", cursor: "pointer",
                      color: model === m.id ? C.cyan : C.text, fontFamily: MONO, fontSize: 12,
                    }}>
                      <span>{m.id}</span><span style={{ color: C.mut2, fontSize: 10 }}>{m.provider}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
            <Pill status="up" />
            <div style={{ display: "flex", alignItems: "center", gap: 10, background: C.panel2, border: `1px solid ${C.borderLit}`, borderRadius: 9, padding: "8px 13px" }}>
              <User size={13} color={C.cyanDim} />
              <span style={{ fontFamily: MONO, fontSize: 12, color: C.text }}>{authUser}</span>
            </div>
            <button onClick={handleLogout} title="Sign out" style={{
              background: C.panel2, border: `1px solid ${C.borderLit}`, borderRadius: 9,
              width: 36, height: 36, display: "grid", placeItems: "center",
              cursor: "pointer", color: C.mut, transition: "all .15s",
            }}>
              <LogOut size={15} />
            </button>
          </div>
        </header>

        {/* Main content */}
        <main key={view} style={{ flex: 1, overflowY: "auto", padding: 26, animation: "jfade .25s ease" }}>
          {view === "chat" && (
            <div style={{ display: "grid", gridTemplateColumns: "1fr 300px", gap: 20, height: "100%" }}>
              <Panel style={{ display: "flex", flexDirection: "column", minHeight: 0 }}>
                <SectionLabel right={<span style={{ fontFamily: MONO, fontSize: 10, color: C.mut2 }}>{model}</span>}>Agent Console</SectionLabel>
                <div style={{ flex: 1, minHeight: 0 }}>
                  <Chat model={model} token={token} sessionId={sessionId} onUnauth={handleLogout} />
                </div>
              </Panel>
              <div style={{ display: "flex", flexDirection: "column", gap: 16, minHeight: 0 }}>
                <Panel>
                  <SectionLabel>System Vitals</SectionLabel>
                  {services.length > 0
                    ? services.map(s => <ServiceRow key={s.key} s={s} compact />)
                    : <div style={{ fontFamily: MONO, fontSize: 11, color: C.mut2, textAlign: "center", padding: 16 }}>Loading…</div>}
                </Panel>
                <Panel style={{ flex: 1, overflowY: "auto" }}>
                  <SectionLabel>Connected Servers</SectionLabel>
                  {servers.length > 0
                    ? servers.map(s => {
                        const status = serverStatus(s);
                        const st = STATUS[status] || STATUS.down;
                        return (
                          <div key={s.name} style={{ display: "flex", alignItems: "center", gap: 10, padding: "9px 0", borderBottom: `1px solid ${C.border}` }}>
                            <Dot color={st.c} />
                            <span style={{ fontFamily: MONO, fontSize: 12.5, color: C.text, flex: 1 }}>{s.name}</span>
                            <span style={{ fontFamily: MONO, fontSize: 10.5, color: C.mut2 }}>{(s.tools ?? []).length} tools</span>
                          </div>
                        );
                      })
                    : <div style={{ fontFamily: MONO, fontSize: 11, color: C.mut2, textAlign: "center", padding: 16 }}>No servers</div>}
                </Panel>
              </div>
            </div>
          )}
          {view === "status"  && <ServicesView services={services} servers={servers} />}
          {view === "servers" && <ServersView servers={servers} token={token} onRefresh={fetchServers} onUnauth={handleLogout} />}
          {view === "tools"   && <ToolsView tools={allTools} />}
        </main>
      </div>
    </div>
  );
}

createRoot(document.getElementById("root")).render(<App />);
