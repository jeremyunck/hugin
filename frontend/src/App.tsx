import { useCallback, useEffect, useRef, useState, type ReactNode, type RefObject } from "react";
import {
  ArrowLeft,
  BatteryFull,
  Check,
  Box,
  ChevronDown,
  ChevronRight,
  FileText,
  Folder,
  FolderOpen,
  Github,
  History,
  Lock,
  Menu,
  MessageCirclePlus,
  MessageSquare,
  Network,
  Plus,
  Puzzle,
  Search,
  Send,
  Signal,
  SlidersHorizontal,
  User,
  Wifi,
  X
} from "lucide-react";

const LOGO =
  "data:image/png;base64,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";

const COLORS = {
  ink: "#1C1F23",
  muted: "#8B9099",
  faint: "#ADB2BA",
  micro: "#BFC3CA",
  border: "#EAEBED",
  badge: "#F1F2F4",
  hover: "#F4F4F6",
  bubble: "#EFEFF1",
  green: "#20A65A"
};

const CHIPS = [
  ["Summarize a document", "Summarize a document"],
  ["Analyze data", "Analyze this dataset and show key trends with a chart."],
  ["Write code", "Write a Python script to clean a CSV file."],
  ["Brainstorm ideas", "Brainstorm ideas for a product launch."],
  ["Show me tips", "Show me tips for getting the most out of Hugin."]
] as const;

type Screen = "login" | "chat" | "purechat" | "history" | "integrations";

type FileItem = {
  name: string;
  size: string;
  isNew?: boolean;
};

type MessageEvent = {
  verb: string;
  file: string;
};

type MessageRef = {
  name: string;
};

type MessageItem = {
  role: "user" | "assistant";
  text?: string;
  typing?: boolean;
  bullets?: string[];
  refs?: MessageRef[];
  events: MessageEvent[];
};

type HistoryItem = {
  id: string;
  group: "Today" | "Earlier";
  title: string;
  when: string;
  count: number;
  restore: {
    sessionId: string;
    files: FileItem[];
    messages: Omit<MessageItem, "events">[];
  };
};

const HISTORY: HistoryItem[] = [
  {
    id: "sales",
    group: "Today",
    title: "Sales data analysis",
    when: "2h ago",
    count: 14,
    restore: {
      sessionId: "3f9c1a2b",
      files: [
        { name: "sales_data.csv", size: "12.6 kb" },
        { name: "summary.md", size: "1.8 kb" },
        { name: "chart.png", size: "38.4 kb" }
      ],
      messages: [
        { role: "user", text: "Can you summarize key sales trends from this data?" },
        {
          role: "assistant",
          text: "Here's a summary of the key sales trends:",
          bullets: ["Revenue increased 23% QoQ", "Top growth in West region", "Q4 shows seasonal peak"],
          refs: [{ name: "sales_summary.md" }]
        }
      ]
    }
  },
  {
    id: "mkt",
    group: "Today",
    title: "Marketing strategy brainstorm",
    when: "Yesterday",
    count: 18,
    restore: {
      sessionId: "a17c40e9",
      files: [
        { name: "campaign_brief.md", size: "3.2 kb" },
        { name: "audience.csv", size: "5.1 kb" }
      ],
      messages: [
        { role: "user", text: "Help me brainstorm a Q3 marketing strategy." },
        {
          role: "assistant",
          text: "Let's start with three angles:",
          bullets: ["Lifecycle email re-engagement", "A referral loop with incentives", "A focused paid-social test"]
        }
      ]
    }
  },
  {
    id: "code",
    group: "Today",
    title: "Code review assistance",
    when: "2 days ago",
    count: 9,
    restore: {
      sessionId: "5d2b8f3c",
      files: [
        { name: "auth.py", size: "4.7 kb" },
        { name: "review.md", size: "2.0 kb" }
      ],
      messages: [
        { role: "user", text: "Review this authentication module for issues." },
        {
          role: "assistant",
          text: "I found two things worth tightening:",
          bullets: ["Token expiry handling on refresh", "Missing rate limit on the login route"]
        }
      ]
    }
  },
  {
    id: "prod",
    group: "Earlier",
    title: "Product requirements",
    when: "3 days ago",
    count: 12,
    restore: {
      sessionId: "9e6a1b74",
      files: [{ name: "requirements.md", size: "6.4 kb" }],
      messages: [
        { role: "user", text: "Draft requirements for the onboarding flow." },
        {
          role: "assistant",
          text: "Here's a first cut covering the core states:",
          bullets: ["Account creation", "Welcome checklist", "Empty-state guidance"]
        }
      ]
    }
  },
  {
    id: "research",
    group: "Earlier",
    title: "Research summary",
    when: "Last week",
    count: 8,
    restore: {
      sessionId: "c084d2f1",
      files: [
        { name: "research.md", size: "9.3 kb" },
        { name: "sources.csv", size: "2.7 kb" }
      ],
      messages: [
        { role: "user", text: "Summarize the competitor research doc." },
        {
          role: "assistant",
          text: "Three takeaways stood out:",
          bullets: ["Competitors lead on price", "None match real-time sandbox", "Sandbox is our clearest wedge"]
        }
      ]
    }
  },
  {
    id: "api",
    group: "Earlier",
    title: "API integration help",
    when: "Last week",
    count: 6,
    restore: {
      sessionId: "b3f7c9a0",
      files: [{ name: "integration.md", size: "3.8 kb" }],
      messages: [
        { role: "user", text: "How do I connect the Stripe webhook?" },
        {
          role: "assistant",
          text: "Here's the path that works cleanly:",
          bullets: ["Verify the signing secret", "Handle events idempotently", "Return 200 fast, process async"]
        }
      ]
    }
  }
];

const HISTORY_GROUPS: Array<["Today" | "Earlier", string]> = [
  ["Today", "TODAY"],
  ["Earlier", "EARLIER"]
];

const MENU_ITEMS = [
  ["New chat", MessageCirclePlus, "chat"],
  ["New sandbox", Box, "sandbox"],
  ["History", History, "history"],
  ["Integrations", Puzzle, "integrations"]
] as const;

function newId() {
  return Array.from({ length: 8 }, () => Math.floor(Math.random() * 16).toString(16)).join("");
}

function isAnalyze(text: string) {
  return /analy|dataset|trend|chart/i.test(text);
}

function StatusBar() {
  return (
    <div className="status-bar">
      <span className="status-time">9:41</span>
      <div className="status-notch" />
      <div className="status-icons">
        <Signal size={16} strokeWidth={2.4} />
        <Wifi size={16} strokeWidth={2.4} />
        <BatteryFull size={22} strokeWidth={1.8} />
      </div>
    </div>
  );
}

function AppHeader({ onMenu }: { onMenu: () => void }) {
  return (
    <div className="app-header">
      <div className="brand">
        <img src={LOGO} alt="Hugin" className="brand-logo" />
        <span className="brand-text">HUGIN</span>
      </div>
      <button type="button" className="icon-button" onClick={onMenu} aria-label="Open menu">
        <Menu size={22} strokeWidth={2} />
      </button>
    </div>
  );
}

function TreeRow({
  depth = 0,
  onClick,
  children
}: {
  depth?: number;
  onClick?: () => void;
  children: ReactNode;
}) {
  return (
    <div className={`tree-row ${onClick ? "tree-row-clickable" : ""}`} style={{ paddingLeft: depth * 16 }} onClick={onClick}>
      {children}
    </div>
  );
}

function FileTree(props: {
  sessionId: string;
  wsOpen: boolean;
  chartsOpen: boolean;
  files: FileItem[];
  hasCharts: boolean | "new";
  onToggleWs: () => void;
  onToggleCharts: () => void;
}) {
  const { sessionId, wsOpen, chartsOpen, files, hasCharts, onToggleWs, onToggleCharts } = props;

  return (
    <div className="file-tree">
      <TreeRow>
        <ChevronDown size={13} color={COLORS.faint} />
        <Network size={14} strokeWidth={2} color={COLORS.ink} />
        <span className="mono">~/sessions/{sessionId}</span>
        <span className="tree-badge">sandbox</span>
      </TreeRow>

      <TreeRow depth={1} onClick={onToggleWs}>
        {wsOpen ? <ChevronDown size={13} color={COLORS.faint} /> : <ChevronRight size={13} color={COLORS.faint} />}
        {wsOpen ? <FolderOpen size={14} strokeWidth={2} color={COLORS.ink} /> : <Folder size={14} strokeWidth={2} color={COLORS.ink} />}
        <span>workspace</span>
      </TreeRow>

      {wsOpen ? (
        <div>
          {files.map((file) => (
            <div key={file.name} className={file.isNew ? "file-highlight" : undefined}>
              <TreeRow depth={2}>
                <FileText size={13.5} strokeWidth={2} color={COLORS.muted} />
                <span className="mono">{file.name}</span>
                <span className="tree-size mono">{file.size}</span>
              </TreeRow>
            </div>
          ))}
          {hasCharts ? (
            <>
              <div className={hasCharts === "new" ? "file-highlight" : undefined}>
                <TreeRow depth={2} onClick={onToggleCharts}>
                  {chartsOpen ? <ChevronDown size={13} color={COLORS.faint} /> : <ChevronRight size={13} color={COLORS.faint} />}
                  {chartsOpen ? <FolderOpen size={14} strokeWidth={2} color={COLORS.ink} /> : <Folder size={14} strokeWidth={2} color={COLORS.ink} />}
                  <span>charts</span>
                </TreeRow>
              </div>
              {chartsOpen ? (
                <TreeRow depth={3}>
                  <FileText size={13.5} strokeWidth={2} color={COLORS.muted} />
                  <span className="mono">sales_trend.png</span>
                  <span className="tree-size mono">47.2 kb</span>
                </TreeRow>
              ) : null}
            </>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

function Greeting({ onChip }: { onChip: (prompt: string) => void }) {
  return (
    <div className="greeting">
      <h1>Hi Alex! 👋</h1>
      <p>How can I help you today?</p>
      <div className="chip-list">
        {CHIPS.map(([label, prompt]) => (
          <button key={label} type="button" className="chip" onClick={() => onChip(prompt)}>
            {label}
          </button>
        ))}
      </div>
    </div>
  );
}

function TypingDots() {
  return (
    <span className="typing-dots">
      <span className="dot" />
      <span className="dot" />
      <span className="dot" />
    </span>
  );
}

function Messages({ items, listRef }: { items: MessageItem[]; listRef: RefObject<HTMLDivElement> }) {
  return (
    <div ref={listRef} className="messages">
      {items.map((message, index) =>
        message.role === "user" ? (
          <div key={index} className="message-row message-row-user fade-in">
            <div className="message-bubble message-bubble-user">{message.text}</div>
          </div>
        ) : (
          <div key={index} className="message-row message-row-assistant fade-in">
            <div className="message-bubble message-bubble-assistant">
              {message.typing ? <TypingDots /> : <span>{message.text}</span>}
              {message.bullets?.length ? (
                <ul className="assistant-bullets">
                  {message.bullets.map((bullet) => (
                    <li key={bullet}>
                      <span className="bullet-mark">•</span>
                      <span>{bullet}</span>
                    </li>
                  ))}
                </ul>
              ) : null}
              {message.refs?.length ? (
                <div className="assistant-refs">
                  {message.refs.map((ref) => (
                    <div key={ref.name} className="assistant-ref">
                      <FileText size={14} strokeWidth={2} color={COLORS.muted} />
                      <span className="mono">{ref.name}</span>
                    </div>
                  ))}
                </div>
              ) : null}
              {message.events.length ? (
                <div className="assistant-events">
                  {message.events.map((event, eventIndex) => (
                    <div key={`${event.file}-${eventIndex}`} className="assistant-event fade-in">
                      <Check size={15} strokeWidth={3} color={COLORS.green} />
                      <span>
                        {event.verb} <span className="mono">{event.file}</span>
                      </span>
                    </div>
                  ))}
                </div>
              ) : null}
            </div>
          </div>
        )
      )}
    </div>
  );
}

function InputBar(props: {
  value: string;
  disabled: boolean;
  onChange: (value: string) => void;
  onSend: () => void;
}) {
  const { value, disabled, onChange, onSend } = props;

  return (
    <div className="input-wrap">
      <div className="input-bar">
        <input
          value={value}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter" && !disabled) onSend();
          }}
          placeholder="Message Hugin…"
        />
        <button type="button" onClick={onSend} disabled={disabled || !value.trim()} aria-label="Send message">
          <Send size={19} strokeWidth={2} color={COLORS.ink} />
        </button>
      </div>
      <p className="input-note">Hugin can make mistakes. Please verify important information.</p>
    </div>
  );
}

function MenuOverlay(props: {
  onClose: () => void;
  onSandbox: () => void;
  onChat: () => void;
  onHistory: () => void;
  onIntegrations: () => void;
}) {
  const { onClose, onSandbox, onChat, onHistory, onIntegrations } = props;

  return (
    <div className="menu-overlay">
      <button type="button" className="menu-backdrop backdrop-fade" onClick={onClose} aria-label="Close menu" />
      <div className="menu-panel panel-in">
        <div className="menu-close">
          <button type="button" className="icon-button" onClick={onClose} aria-label="Close menu">
            <X size={22} strokeWidth={2} />
          </button>
        </div>

        <nav className="menu-nav">
          {MENU_ITEMS.map(([label, Icon, action]) => {
            const handler = action === "sandbox" ? onSandbox : action === "chat" ? onChat : action === "history" ? onHistory : onIntegrations;
            return (
              <button key={label} type="button" className={`menu-item ${action === "chat" ? "menu-item-active" : ""}`} onClick={handler}>
                <Icon size={18} strokeWidth={2} color={COLORS.ink} />
                <span>{label}</span>
              </button>
            );
          })}
        </nav>

        <div className="menu-profile">
          <div className="profile-avatar">AS</div>
          <div className="profile-copy">
            <div className="profile-name">Alex Sampson</div>
            <div className="profile-email">alex@example.com</div>
          </div>
          <ChevronRight size={18} color={COLORS.faint} />
        </div>
      </div>
    </div>
  );
}

function HistoryScreen(props: {
  onMenu: () => void;
  onOpen: (historyItem: HistoryItem) => void;
  onNew: () => void;
  query: string;
  onQuery: (value: string) => void;
}) {
  const { onMenu, onOpen, onNew, query, onQuery } = props;
  const lower = query.trim().toLowerCase();
  const match = (item: HistoryItem) => !lower || item.title.toLowerCase().includes(lower);
  const anyResults = HISTORY.some(match);

  return (
    <>
      <AppHeader onMenu={onMenu} />
      <h1 className="screen-title">History</h1>

      <div className="screen-pad">
        <div className="search-bar">
          <Search size={17} strokeWidth={2} color={COLORS.faint} />
          <input
            value={query}
            onChange={(event) => onQuery(event.target.value)}
            placeholder="Search history…"
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
          />
          <SlidersHorizontal size={16} strokeWidth={2} color={COLORS.faint} />
        </div>
      </div>

      <div className="history-list">
        {HISTORY_GROUPS.map(([group, label], groupIndex) => {
          const items = HISTORY.filter((item) => item.group === group && match(item));
          if (!items.length) return null;

          return (
            <div key={group} className={groupIndex > 0 ? "history-group history-group-spaced" : "history-group"}>
              <div className="history-group-label">{label}</div>
              <div className="history-cards">
                {items.map((item) => (
                  <button key={item.id} type="button" className="history-card" onClick={() => onOpen(item)}>
                    <div className="history-card-icon">
                      <MessageSquare size={17} strokeWidth={2} color={COLORS.ink} />
                    </div>
                    <div className="history-card-copy">
                      <div className="history-card-title">{item.title}</div>
                      <div className="history-card-meta">
                        {item.when} · {item.count} messages
                      </div>
                    </div>
                    <ChevronRight size={18} color={COLORS.faint} />
                  </button>
                ))}
              </div>
            </div>
          );
        })}
        {!anyResults ? <p className="history-empty">No sessions match “{query.trim()}”.</p> : null}
      </div>

      <div className="screen-pad history-footer">
        <button type="button" className="primary-button" onClick={onNew}>
          <Plus size={18} strokeWidth={2.4} /> New sandbox
        </button>
      </div>
    </>
  );
}

function IntegrationsScreen(props: {
  onBack: () => void;
  connected: boolean;
  connecting: boolean;
  onConnect: () => void;
}) {
  const { onBack, connected, connecting, onConnect } = props;

  return (
    <>
      <div className="back-row">
        <button type="button" className="icon-button back-button" onClick={onBack} aria-label="Back">
          <ArrowLeft size={22} strokeWidth={2} />
        </button>
      </div>

      <div className="screen-pad">
        <h1 className="screen-title integration-title">Integrations</h1>
        <p className="integration-subtitle">Manage your connected services.</p>
      </div>

      <div className="integrations-list">
        <div className="history-group-label">OTHER</div>
        <div className="integration-card">
          <Github size={26} strokeWidth={1.7} color={COLORS.ink} />
          <div className="integration-copy">
            <div className="integration-name-row">
              <span className="integration-name">GitHub</span>
              {connected ? <span className="integration-badge">CONNECTED</span> : null}
            </div>
            <div className="integration-meta">Issues, PRs, and repo files</div>
          </div>
          <div className="integration-action">
            {connected ? (
              <button type="button" className="secondary-button">
                Manage
              </button>
            ) : (
              <button type="button" className="dark-button" onClick={onConnect} disabled={connecting}>
                {connecting ? "Connecting…" : "Connect"}
              </button>
            )}
          </div>
        </div>
      </div>
    </>
  );
}

function Field(props: {
  icon: typeof User;
  label: string;
  value: string;
  type?: string;
  placeholder: string;
  onChange: (value: string) => void;
  onEnter?: () => void;
}) {
  const { icon: Icon, label, value, type = "text", placeholder, onChange, onEnter } = props;

  return (
    <label className="login-field">
      <span>{label}</span>
      <div className="login-input">
        <Icon size={18} strokeWidth={2} color={COLORS.faint} />
        <input
          type={type}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") onEnter?.();
          }}
          placeholder={placeholder}
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
        />
      </div>
    </label>
  );
}

function LoginScreen(props: {
  username: string;
  password: string;
  onUser: (value: string) => void;
  onPass: (value: string) => void;
  onSignIn: () => void;
}) {
  const { username, password, onUser, onPass, onSignIn } = props;
  const ready = username.trim() && password.trim();

  return (
    <div className="login-screen">
      <div className="login-brand">
        <img src={LOGO} alt="Hugin" className="login-logo" />
        <span className="login-wordmark">HUGIN</span>
        <p>Sign in to your workspace</p>
      </div>

      <div className="login-fields">
        <Field icon={User} label="Username" value={username} placeholder="Enter your username" onChange={onUser} onEnter={() => ready && onSignIn()} />
        <Field
          icon={Lock}
          label="Password"
          type="password"
          value={password}
          placeholder="Enter your password"
          onChange={onPass}
          onEnter={() => ready && onSignIn()}
        />
      </div>

      <button type="button" className="signin-button" disabled={!ready} onClick={() => ready && onSignIn()}>
        Sign in
      </button>
    </div>
  );
}

export default function App() {
  const [screen, setScreen] = useState<Screen>("login");
  const [menuOpen, setMenuOpen] = useState(false);
  const [sessionId, setSessionId] = useState("7b2e9d3a");
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [historyQuery, setHistoryQuery] = useState("");
  const [returnScreen, setReturnScreen] = useState<Screen>("chat");
  const [ghConnected, setGhConnected] = useState(false);
  const [ghConnecting, setGhConnecting] = useState(false);
  const [messages, setMessages] = useState<MessageItem[]>([]);
  const [wsOpen, setWsOpen] = useState(false);
  const [chartsOpen, setChartsOpen] = useState(false);
  const [files, setFiles] = useState<FileItem[]>([{ name: "data.csv", size: "8.4 kb" }]);
  const [hasCharts, setHasCharts] = useState<boolean | "new">(false);

  const listRef = useRef<HTMLDivElement>(null);
  const timers = useRef<number[]>([]);

  const clearTimers = useCallback(() => {
    timers.current.forEach((timer) => window.clearTimeout(timer));
    timers.current = [];
  }, []);

  const after = useCallback((ms: number, fn: () => void) => {
    timers.current.push(window.setTimeout(fn, ms));
  }, []);

  useEffect(() => {
    if (listRef.current) listRef.current.scrollTop = listRef.current.scrollHeight;
  }, [messages]);

  useEffect(() => () => clearTimers(), [clearTimers]);

  const resetSession = useCallback(
    (id?: string) => {
      clearTimers();
      setSessionId(id ?? newId());
      setMessages([]);
      setWsOpen(false);
      setChartsOpen(false);
      setFiles([{ name: "data.csv", size: "8.4 kb" }]);
      setHasCharts(false);
      setBusy(false);
      setDraft("");
    },
    [clearTimers]
  );

  const startSandbox = useCallback(() => {
    resetSession(newId());
    setHistoryQuery("");
    setScreen("chat");
    setMenuOpen(false);
  }, [resetSession]);

  const startChat = useCallback(() => {
    resetSession(newId());
    setHistoryQuery("");
    setScreen("purechat");
    setMenuOpen(false);
  }, [resetSession]);

  const openHistory = useCallback(
    (historyItem: HistoryItem) => {
      clearTimers();
      const restored = historyItem.restore;
      setSessionId(restored.sessionId);
      setMessages(restored.messages.map((message) => ({ ...message, events: [] })));
      setFiles(restored.files);
      setWsOpen(true);
      setChartsOpen(false);
      setHasCharts(false);
      setBusy(false);
      setScreen("chat");
      setMenuOpen(false);
    },
    [clearTimers]
  );

  const openIntegrations = useCallback(() => {
    setReturnScreen(screen);
    setScreen("integrations");
    setMenuOpen(false);
  }, [screen]);

  const connectGithub = useCallback(() => {
    if (ghConnecting || ghConnected) return;
    setGhConnecting(true);
    window.setTimeout(() => {
      setGhConnecting(false);
      setGhConnected(true);
    }, 700);
  }, [ghConnected, ghConnecting]);

  const send = useCallback(
    (textArg?: string) => {
      const text = (textArg ?? draft).trim();
      if (!text || busy) return;

      setDraft("");
      setBusy(true);
      setMessages((current) => [...current, { role: "user", text, events: [] }]);

      after(550, () => {
        setMessages((current) => [...current, { role: "assistant", typing: true, events: [] }]);
      });

      const pure = screen === "purechat";

      if (!pure && isAnalyze(text)) {
        after(1450, () => {
          setMessages((current) => {
            const next = [...current];
            next[next.length - 1] = {
              role: "assistant",
              text: "I'll analyze the data and create a summary with key trends.",
              events: []
            };
            return next;
          });
        });
        after(1900, () => setWsOpen(true));
        after(2550, () => {
          setFiles((current) => [{ name: "analysis.md", size: "2.1 kb", isNew: true }, ...current]);
          setMessages((current) => {
            const next = [...current];
            const last = next[next.length - 1];
            next[next.length - 1] = { ...last, events: [...last.events, { verb: "Created", file: "analysis.md" }] };
            return next;
          });
        });
        after(3300, () => setFiles((current) => current.map((file) => ({ ...file, isNew: false }))));
        after(3450, () => {
          setHasCharts("new");
          setMessages((current) => {
            const next = [...current];
            const last = next[next.length - 1];
            next[next.length - 1] = { ...last, events: [...last.events, { verb: "Generated", file: "sales_trend.png" }] };
            return next;
          });
        });
        after(4200, () => {
          setHasCharts(true);
          setBusy(false);
        });
      } else {
        after(1500, () => {
          setMessages((current) => {
            const next = [...current];
            next[next.length - 1] = {
              role: "assistant",
              text: pure
                ? "Happy to help — ask me anything and we can talk it through."
                : "Got it — I'm on it inside this sandbox. Ask me to analyze data to watch your files update in real time.",
              events: []
            };
            return next;
          });
          setBusy(false);
        });
      }
    },
    [after, busy, draft, screen]
  );

  const fresh = messages.length === 0;

  return (
    <div className="mock-page">
      <div className="device-shell">
        <StatusBar />

        {screen === "login" ? (
          <LoginScreen username={username} password={password} onUser={setUsername} onPass={setPassword} onSignIn={() => setScreen("purechat")} />
        ) : screen === "chat" || screen === "purechat" ? (
          <>
            <AppHeader onMenu={() => setMenuOpen(true)} />
            {screen === "chat" ? (
              <FileTree
                sessionId={sessionId}
                wsOpen={wsOpen}
                chartsOpen={chartsOpen}
                files={files}
                hasCharts={hasCharts}
                onToggleWs={() => setWsOpen((current) => !current)}
                onToggleCharts={() => setChartsOpen((current) => !current)}
              />
            ) : null}
            {fresh ? (
              <div className="chat-body">
                <Greeting onChip={send} />
              </div>
            ) : (
              <Messages items={messages} listRef={listRef} />
            )}
            <InputBar value={draft} onChange={setDraft} onSend={() => send()} disabled={busy} />
          </>
        ) : screen === "integrations" ? (
          <IntegrationsScreen onBack={() => setScreen(returnScreen)} connected={ghConnected} connecting={ghConnecting} onConnect={connectGithub} />
        ) : (
          <HistoryScreen onMenu={() => setMenuOpen(true)} onOpen={openHistory} onNew={startSandbox} query={historyQuery} onQuery={setHistoryQuery} />
        )}

        {menuOpen ? (
          <MenuOverlay
            onClose={() => setMenuOpen(false)}
            onSandbox={startSandbox}
            onChat={startChat}
            onHistory={() => {
              setHistoryQuery("");
              setScreen("history");
              setMenuOpen(false);
            }}
            onIntegrations={openIntegrations}
          />
        ) : null}
      </div>
    </div>
  );
}
