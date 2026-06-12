import type { ReactNode } from "react";
import { Menu, Plus, Clock3, Settings2, Paintbrush, ShieldCheck, Plug } from "lucide-react";
import { RavenMark } from "./RavenMark";
import { Button, NavRow } from "./Ui";
import type { DrawerScreen, Route } from "../lib/types";
import { toHash } from "../lib/routing";

const drawerItems: Array<{ label: string; screen: DrawerScreen; subtitle: string; icon: typeof Plus }> = [
  { label: "New Chat", screen: "new-chat", subtitle: "Start fresh", icon: Plus },
  { label: "History", screen: "history", subtitle: "Saved conversations", icon: Clock3 },
  { label: "Settings", screen: "settings", subtitle: "General preferences", icon: Settings2 },
  { label: "Integrations", screen: "integrations", subtitle: "Connected services", icon: Plug },
  { label: "Appearance", screen: "appearance", subtitle: "Theme and density", icon: Paintbrush },
  { label: "Data & Privacy", screen: "data-privacy", subtitle: "Stored data", icon: ShieldCheck }
];

export function Layout({
  route,
  drawerOpen,
  onOpenDrawer,
  onCloseDrawer,
  onNavigate,
  sidebarContent,
  children
}: {
  route: Route;
  drawerOpen: boolean;
  onOpenDrawer: () => void;
  onCloseDrawer: () => void;
  onNavigate: (hash: string) => void;
  sidebarContent: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="guild-shell">
      <aside className="guild-sidebar desktop-only">
        <div className="brand-card">
          <div className="brand-mark">
            <RavenMark className="brand-mark-icon" />
          </div>
          <div className="brand-copy">
            <div className="eyebrow">Guild</div>
            <div className="brand-title">Hugin</div>
          </div>
        </div>
        {sidebarContent}
      </aside>

      <div className={`drawer-backdrop ${drawerOpen ? "open" : ""}`} onClick={onCloseDrawer} aria-hidden="true" />
      <aside className={`mobile-drawer ${drawerOpen ? "open" : ""}`} aria-label="Mobile menu">
        <div className="drawer-head">
          <div className="brand-card compact">
            <div className="brand-mark">
              <RavenMark className="brand-mark-icon" />
            </div>
            <div>
              <div className="eyebrow">Guild</div>
              <div className="brand-title">Hugin</div>
            </div>
          </div>
          <Button variant="icon" onClick={onCloseDrawer} aria-label="Close menu">
            <span>×</span>
          </Button>
        </div>
        <nav className="drawer-nav">
          {drawerItems.map((item) => {
            const active = isRouteActive(route, item.screen);
            const Icon = item.icon;
            return (
              <NavRow
                key={item.screen}
                title={item.label}
                subtitle={item.subtitle}
                icon={<Icon size={16} />}
                active={active}
                onClick={() => {
                  onNavigate(toHash(screenToRoute(item.screen)));
                  onCloseDrawer();
                }}
              />
            );
          })}
        </nav>
      </aside>

      <div className="guild-main">
        <header className="mobile-topbar mobile-only">
          <div className="topbar-title">
            <div className="eyebrow">Guild</div>
            <div className="topbar-name">Hugin</div>
          </div>
          <Button variant="icon" onClick={onOpenDrawer} aria-label="Open menu">
            <Menu size={18} />
          </Button>
        </header>
        {children}
      </div>
    </div>
  );
}

function isRouteActive(route: Route, screen: DrawerScreen) {
  switch (screen) {
    case "new-chat":
      return route.screen === "new-chat";
    case "history":
      return route.screen === "history" || route.screen === "history-chat";
    case "settings":
      return route.screen === "settings";
    case "integrations":
      return route.screen === "integrations" || route.screen === "google-workspace";
    case "appearance":
      return route.screen === "appearance";
    case "data-privacy":
      return route.screen === "data-privacy";
  }
}

function screenToRoute(screen: DrawerScreen): Route {
  switch (screen) {
    case "new-chat":
      return { screen: "new-chat" };
    case "history":
      return { screen: "history" };
    case "settings":
      return { screen: "settings" };
    case "integrations":
      return { screen: "integrations" };
    case "appearance":
      return { screen: "appearance" };
    case "data-privacy":
      return { screen: "data-privacy" };
  }
}
