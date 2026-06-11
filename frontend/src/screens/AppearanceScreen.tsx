import { ArrowLeft } from "lucide-react";
import type { ReactNode } from "react";
import { Button, Card } from "../components/Ui";
import type { AppearanceSettings, Route } from "../lib/types";

export function AppearanceScreen({
  appearance,
  onThemeChange,
  onTextSizeChange,
  onReduceMotionChange,
  onNavigate,
  onOpenDrawer
}: {
  appearance: AppearanceSettings;
  onThemeChange: (theme: AppearanceSettings["theme"]) => void;
  onTextSizeChange: (size: AppearanceSettings["textSize"]) => void;
  onReduceMotionChange: (value: boolean) => void;
  onNavigate: (route: Route) => void;
  onOpenDrawer: () => void;
}) {
  return (
    <section className="screen-frame">
      <div className="screen-head">
        <div className="screen-title-block">
          <Button variant="ghost" className="back-button mobile-only-inline" onClick={() => onNavigate({ screen: "settings" })}>
            <ArrowLeft size={16} />
            Back
          </Button>
          <div className="eyebrow">Guild</div>
          <h1>Appearance</h1>
          <p>Keep the interface minimal and readable.</p>
        </div>
        <Button variant="ghost" className="mobile-only-inline" onClick={onOpenDrawer}>
          Menu
        </Button>
      </div>

      <Card className="appearance-card">
        <OptionGroup title="Theme">
          <ToggleChoice label="Light" active={appearance.theme === "light"} onClick={() => onThemeChange("light")} />
        </OptionGroup>

        <OptionGroup title="Text size">
          <ToggleChoice label="Small" active={appearance.textSize === "small"} onClick={() => onTextSizeChange("small")} />
          <ToggleChoice label="Medium" active={appearance.textSize === "medium"} onClick={() => onTextSizeChange("medium")} />
          <ToggleChoice label="Large" active={appearance.textSize === "large"} onClick={() => onTextSizeChange("large")} />
        </OptionGroup>

        <OptionGroup title="Motion">
          <ToggleChoice
            label="Reduce motion"
            active={appearance.reduceMotion}
            onClick={() => onReduceMotionChange(!appearance.reduceMotion)}
          />
        </OptionGroup>
      </Card>
    </section>
  );
}

function OptionGroup({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="option-group">
      <div className="settings-section-title">{title}</div>
      <div className="toggle-row">{children}</div>
    </div>
  );
}

function ToggleChoice({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button className={`toggle-choice ${active ? "active" : ""}`} onClick={onClick}>
      {label}
    </button>
  );
}
