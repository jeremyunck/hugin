import React, { useState, useEffect, useRef } from "react";
import { createRoot } from "react-dom/client";
import {
  MessageSquare, Activity, Server, Wrench, Search, Send, Cpu,
  Database, Globe, RefreshCw, Zap, ChevronDown, Terminal,
  Plug, Clock, FolderTree, CheckCircle2, AlertTriangle, XCircle, Settings, Bird
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

const SERVICES = [
  { key: "agent",  name: "Agent Server",     detail: "localhost:8080 · /actuator/health",          icon: Cpu,      status: "up",       meta: "uptime 4d 6h" },
  { key: "llm",    name: "LLM Provider",      detail: "OpenRouter · openai/gpt-oss-120b",           icon: Zap,      status: "up",       meta: "142ms avg" },
  { key: "memory", name: "Long-term Memory",  detail: "Redis · agent:memory · top-k 3",             icon: Database, status: "up",       meta: "418 vectors" },
  { key: "search", name: "Web Search MCP",    detail: "perplexity/sonar via OpenRouter",            icon: Globe,    status: "degraded", meta: "rate-limited" },
];

const SERVERS = [
  { name: "time",       transport: "stdio", cmd: "uvx mcp-server-time",                               status: "connected", tools: ["get_current_time", "convert_time"],                                    icon: Clock },
  { name: "web-search", transport: "stdio", cmd: "python3 openrouter-search-mcp.py",                  status: "connected", tools: ["web_search"],                                                          icon: Globe },
  { name: "filesystem", transport: "stdio", cmd: "npx @modelcontextprotocol/server-filesystem /tmp",  status: "connected", tools: ["read_file", "write_file", "list_directory", "create_directory", "move_file"], icon: FolderTree },
  { name: "github",     transport: "sse",   cmd: "https://mcp.github.com/sse",                        status: "error",     tools: [],                                                                      icon: Server },
];

const ALL_TOOLS = SERVERS.flatMap((s) =>
  s.tools.map((t) => ({ name: t, server: s.name, transport: s.transport }))
);

const SEED_CHAT = [
  { role: "assistant", text: "Systems online. Hugin connected to 3 of 4 MCP servers. What do you need?" },
];

const STATUS = {
  up:        { c: C.ok,   label: "OPERATIONAL", Icon: CheckCircle2 },
  connected: { c: C.ok,   label: "CONNECTED",   Icon: CheckCircle2 },
  degraded:  { c: C.warn, label: "DEGRADED",    Icon: AlertTriangle },
  error:     { c: C.err,  label: "ERROR",        Icon: XCircle },
  down:      { c: C.err,  label: "OFFLINE",      Icon: XCircle },
};

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
          <Wrench size={13} /> tool call · {m.server}.{m.tool}
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
      }}>
        {m.text}
      </div>
    </div>
  );
}

function Chat({ model }) {
  const [msgs, setMsgs] = useState(SEED_CHAT);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const endRef = useRef(null);

  useEffect(() => { endRef.current?.scrollIntoView({ behavior: "smooth" }); }, [msgs]);

  const send = () => {
    if (!input.trim() || busy) return;
    const userText = input.trim();
    setMsgs((m) => [...m, { role: "user", text: userText }]);
    setInput("");
    setBusy(true);

    setTimeout(() => {
      setMsgs((m) => [...m, { role: "tool", server: "time", tool: "get_current_time", args: '{ "timezone": "America/Chicago" }' }]);
    }, 600);

    const reply = "It's 4:12 PM in Chicago. The agent routed that through the time MCP server. Anything else I can pull while we're connected?";
    let i = 0;
    setTimeout(() => {
      setMsgs((m) => [...m, { role: "assistant", text: "" }]);
      const tick = setInterval(() => {
        i += 2;
        setMsgs((m) => {
          const copy = [...m];
          copy[copy.length - 1] = { role: "assistant", text: reply.slice(0, i) };
          return copy;
        });
        if (i >= reply.length) { clearInterval(tick); setBusy(false); }
      }, 18);
    }, 1300);
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100%" }}>
      <div style={{ flex: 1, overflowY: "auto", padding: "4px 4px 8px", display: "flex", flexDirection: "column", gap: 16 }}>
        {msgs.map((m, i) => <Bubble key={i} m={m} />)}
        {busy && <span style={{ fontFamily: MONO, fontSize: 11, color: C.cyanDim, letterSpacing: "0.1em" }}>▌ generating…</span>}
        <div ref={endRef} />
      </div>
      <div style={{ marginTop: 14, display: "flex", gap: 10, alignItems: "flex-end" }}>
        <div style={{ flex: 1, background: C.panel2, border: `1px solid ${C.borderLit}`, borderRadius: 12, padding: "12px 14px", display: "flex", alignItems: "center", gap: 10 }}>
          <Terminal size={16} color={C.cyanDim} />
          <input
            value={input} onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && send()}
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
        POST /api/agent/stream · tool calls render inline as the agent invokes them
      </div>
    </div>
  );
}

function ServiceRow({ s, compact }) {
  const st = STATUS[s.status] || STATUS.down;
  const Icon = s.icon;
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
        {!compact && <div style={{ fontFamily: MONO, fontSize: 10, color: C.mut2, marginTop: 6 }}>{s.meta}</div>}
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

function ServicesView() {
  const upCount = SERVICES.filter((s) => s.status === "up").length;
  return (
    <div>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(3,1fr)", gap: 14, marginBottom: 20 }}>
        <Stat label="Services Online" value={`${upCount}/${SERVICES.length}`} accent={C.ok} />
        <Stat label="MCP Servers" value={`${SERVERS.filter(s => s.status === "connected").length}/${SERVERS.length}`} accent={C.cyan} />
        <Stat label="Tools Available" value={ALL_TOOLS.length} accent={C.gold} />
      </div>
      <Panel>
        <SectionLabel>System Vitals</SectionLabel>
        {SERVICES.map((s) => <ServiceRow key={s.key} s={s} />)}
      </Panel>
    </div>
  );
}

function ServersView() {
  const [spin, setSpin] = useState(null);
  const reconnect = (name) => { setSpin(name); setTimeout(() => setSpin(null), 1200); };
  return (
    <div style={{ display: "grid", gridTemplateColumns: "repeat(2,1fr)", gap: 14 }}>
      {SERVERS.map((s) => {
        const st = STATUS[s.status] || STATUS.down;
        const Icon = s.icon;
        return (
          <Panel key={s.name} pad={16}>
            <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between" }}>
              <div style={{ display: "flex", gap: 11, alignItems: "center" }}>
                <div style={{ width: 38, height: 38, borderRadius: 9, background: `${st.c}12`, border: `1px solid ${st.c}30`, display: "grid", placeItems: "center" }}>
                  <Icon size={18} color={st.c} />
                </div>
                <div>
                  <div style={{ fontFamily: DISP, fontSize: 16, fontWeight: 600, color: C.text }}>{s.name}</div>
                  <span style={{ fontFamily: MONO, fontSize: 9.5, letterSpacing: "0.1em", color: C.mut, textTransform: "uppercase", background: C.panel3, padding: "2px 6px", borderRadius: 4 }}>{s.transport}</span>
                </div>
              </div>
              <Pill status={s.status} />
            </div>
            <div style={{ fontFamily: MONO, fontSize: 11, color: C.mut, marginTop: 12, background: C.bg, border: `1px solid ${C.border}`, borderRadius: 7, padding: "8px 10px", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
              $ {s.cmd}
            </div>
            <div style={{ marginTop: 12, display: "flex", flexWrap: "wrap", gap: 6, minHeight: 24 }}>
              {s.tools.length === 0
                ? <span style={{ fontFamily: MONO, fontSize: 11, color: C.err }}>no tools — connection failed</span>
                : s.tools.map((t) => (
                  <span key={t} style={{ fontFamily: MONO, fontSize: 10.5, color: C.cyan, background: `${C.cyan}12`, border: `1px solid ${C.cyan}28`, padding: "3px 8px", borderRadius: 5 }}>{t}</span>
                ))}
            </div>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginTop: 14 }}>
              <span style={{ fontFamily: MONO, fontSize: 10.5, color: C.mut2 }}>{s.tools.length} tool{s.tools.length !== 1 ? "s" : ""}</span>
              <button onClick={() => reconnect(s.name)} style={{
                display: "flex", alignItems: "center", gap: 6, background: C.panel3, color: C.text,
                border: `1px solid ${C.borderLit}`, borderRadius: 7, padding: "6px 11px", fontFamily: MONO, fontSize: 11, cursor: "pointer",
              }}>
                <RefreshCw size={12} style={{ animation: spin === s.name ? "jspin 1s linear infinite" : "none" }} />
                Reconnect
              </button>
            </div>
          </Panel>
        );
      })}
      <button style={{
        gridColumn: "1 / -1", background: "transparent", border: `1.5px dashed ${C.borderLit}`,
        borderRadius: 12, padding: 16, color: C.mut, fontFamily: DISP, fontSize: 13, letterSpacing: "0.1em",
        cursor: "pointer", display: "flex", alignItems: "center", justifyContent: "center", gap: 8,
      }}>
        <Plug size={16} /> ADD MCP SERVER — POST /api/servers
      </button>
    </div>
  );
}

function ToolsView() {
  const [q, setQ] = useState("");
  const list = ALL_TOOLS.filter((t) => t.name.includes(q.toLowerCase()) || t.server.includes(q.toLowerCase()));
  return (
    <div>
      <div style={{ display: "flex", alignItems: "center", gap: 10, background: C.panel2, border: `1px solid ${C.borderLit}`, borderRadius: 10, padding: "10px 14px", marginBottom: 16 }}>
        <Search size={16} color={C.mut} />
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Filter tools…"
          style={{ flex: 1, background: "transparent", border: "none", outline: "none", color: C.text, fontFamily: BODY, fontSize: 14 }} />
        <span style={{ fontFamily: MONO, fontSize: 11, color: C.mut2 }}>{list.length} of {ALL_TOOLS.length}</span>
      </div>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(3,1fr)", gap: 12 }}>
        {list.map((t, i) => (
          <Panel key={i} pad={14} style={{ display: "flex", alignItems: "center", gap: 11 }}>
            <div style={{ width: 32, height: 32, borderRadius: 8, background: `${C.cyan}12`, border: `1px solid ${C.cyan}28`, display: "grid", placeItems: "center", flexShrink: 0 }}>
              <Wrench size={14} color={C.cyan} />
            </div>
            <div style={{ minWidth: 0 }}>
              <div style={{ fontFamily: MONO, fontSize: 12.5, color: C.text, fontWeight: 600, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{t.name}</div>
              <div style={{ fontFamily: MONO, fontSize: 10, color: C.mut, marginTop: 2 }}>{t.server} · {t.transport}</div>
            </div>
          </Panel>
        ))}
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

function App() {
  const [view, setView] = useState("chat");
  const [model, setModel] = useState(MODELS[0].id);
  const [modelOpen, setModelOpen] = useState(false);

  return (
    <div style={{ display: "flex", height: "100vh", background: C.bg, color: C.text, fontFamily: BODY, overflow: "hidden" }}>
      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=Chakra+Petch:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600&family=Sora:wght@400;500;600&display=swap');
        @keyframes jpulse { 0%{transform:scale(1);opacity:.5} 100%{transform:scale(2.6);opacity:0} }
        @keyframes jspin  { to { transform: rotate(360deg) } }
        @keyframes jfade  { from{opacity:0;transform:translateY(8px)} to{opacity:1;transform:none} }
        *::-webkit-scrollbar       { width:8px; height:8px }
        *::-webkit-scrollbar-thumb { background:#222D3D; border-radius:99px }
        *::-webkit-scrollbar-track { background:transparent }
      `}</style>

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
        <div style={{ marginTop: "auto", marginBottom: 18, color: C.mut2, cursor: "pointer" }}>
          <Settings size={18} />
        </div>
      </aside>

      <div style={{ flex: 1, display: "flex", flexDirection: "column", minWidth: 0 }}>
        <header style={{ height: 64, borderBottom: `1px solid ${C.border}`, display: "flex", alignItems: "center", justifyContent: "space-between", padding: "0 26px", flexShrink: 0 }}>
          <div>
            <div style={{ fontFamily: DISP, fontSize: 19, fontWeight: 700, letterSpacing: "0.16em", color: C.text }}>
              HUGIN <span style={{ color: C.cyan }}>·</span> <span style={{ color: C.mut, fontSize: 13, letterSpacing: "0.2em" }}>MCP CONTROL</span>
            </div>
            <div style={{ fontFamily: MONO, fontSize: 10, color: C.mut2, letterSpacing: "0.08em" }}>mcp-client · spring-boot agent · :8080</div>
          </div>
          <div style={{ display: "flex", alignItems: "center", gap: 14 }}>
            <div style={{ position: "relative" }}>
              <button onClick={() => setModelOpen((o) => !o)} style={{
                display: "flex", alignItems: "center", gap: 9, background: C.panel2, border: `1px solid ${C.borderLit}`,
                borderRadius: 9, padding: "8px 13px", color: C.text, cursor: "pointer", fontFamily: MONO, fontSize: 12,
              }}>
                <Zap size={13} color={C.gold} /> {model} <ChevronDown size={13} color={C.mut} />
              </button>
              {modelOpen && (
                <div style={{ position: "absolute", top: 44, right: 0, background: C.panel2, border: `1px solid ${C.borderLit}`, borderRadius: 10, padding: 6, minWidth: 230, zIndex: 20, boxShadow: "0 12px 30px #000a" }}>
                  {MODELS.map((m) => (
                    <button key={m.id} onClick={() => { setModel(m.id); setModelOpen(false); }} style={{
                      display: "flex", justifyContent: "space-between", width: "100%", background: model === m.id ? `${C.cyan}12` : "transparent",
                      border: "none", borderRadius: 7, padding: "9px 11px", cursor: "pointer", color: model === m.id ? C.cyan : C.text, fontFamily: MONO, fontSize: 12,
                    }}>
                      <span>{m.id}</span><span style={{ color: C.mut2, fontSize: 10 }}>{m.provider}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
            <Pill status="up" />
          </div>
        </header>

        <main key={view} style={{ flex: 1, overflowY: "auto", padding: 26, animation: "jfade .25s ease" }}>
          {view === "chat" && (
            <div style={{ display: "grid", gridTemplateColumns: "1fr 300px", gap: 20, height: "100%" }}>
              <Panel style={{ display: "flex", flexDirection: "column", minHeight: 0 }}>
                <SectionLabel right={<span style={{ fontFamily: MONO, fontSize: 10, color: C.mut2 }}>{model}</span>}>Agent Console</SectionLabel>
                <div style={{ flex: 1, minHeight: 0 }}><Chat model={model} /></div>
              </Panel>
              <div style={{ display: "flex", flexDirection: "column", gap: 16, minHeight: 0 }}>
                <Panel>
                  <SectionLabel>System Vitals</SectionLabel>
                  {SERVICES.map((s) => <ServiceRow key={s.key} s={s} compact />)}
                </Panel>
                <Panel style={{ flex: 1 }}>
                  <SectionLabel>Connected Servers</SectionLabel>
                  {SERVERS.map((s) => {
                    const st = STATUS[s.status] || STATUS.down;
                    return (
                      <div key={s.name} style={{ display: "flex", alignItems: "center", gap: 10, padding: "9px 0", borderBottom: `1px solid ${C.border}` }}>
                        <Dot color={st.c} />
                        <span style={{ fontFamily: MONO, fontSize: 12.5, color: C.text, flex: 1 }}>{s.name}</span>
                        <span style={{ fontFamily: MONO, fontSize: 10.5, color: C.mut2 }}>{s.tools.length} tools</span>
                      </div>
                    );
                  })}
                </Panel>
              </div>
            </div>
          )}
          {view === "status"  && <ServicesView />}
          {view === "servers" && <ServersView />}
          {view === "tools"   && <ToolsView />}
        </main>
      </div>
    </div>
  );
}

createRoot(document.getElementById("root")).render(<App />);
