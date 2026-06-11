import { AlertTriangle, X } from "lucide-react";
import { Button, Card } from "../components/Ui";

export function ClearHistoryDialog({
  onConfirm,
  onCancel
}: {
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <div className="modal-backdrop" role="presentation" onClick={onCancel}>
      <Card className="modal-card" onClick={(event) => event.stopPropagation()}>
        <div className="modal-head">
          <div className="modal-icon">
            <AlertTriangle size={18} />
          </div>
          <Button variant="icon" onClick={onCancel} aria-label="Close dialog">
            <X size={16} />
          </Button>
        </div>
        <h2>Clear chat history?</h2>
        <p>This removes saved conversations from the mock data store. Settings and integrations stay intact.</p>
        <div className="modal-actions">
          <Button variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
          <Button variant="danger" onClick={onConfirm}>
            Clear history
          </Button>
        </div>
      </Card>
    </div>
  );
}
