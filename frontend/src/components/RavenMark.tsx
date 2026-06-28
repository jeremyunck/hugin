export function RavenMark({ className = "", title = "Guild raven mark" }: { className?: string; title?: string }) {
  return (
    <img src="/bouw-logo.png" className={className} alt={title} />
  );
}
