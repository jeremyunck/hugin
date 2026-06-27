export function RavenMark({ className = "", title = "Guild raven mark" }: { className?: string; title?: string }) {
  return (
    <img src="/bouw-bird.jpg" className={className} alt={title} />
  );
}
