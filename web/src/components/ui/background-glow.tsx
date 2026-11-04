export function BackgroundGlow() {
  return (
    <div className="fixed inset-0 overflow-hidden pointer-events-none z-[-1]" aria-hidden="true">
      {/* Primary Luminous Aurora (Top Right) */}
      <div
        className="absolute w-[650px] h-[650px] rounded-full opacity-60"
        style={{
          background: 'radial-gradient(circle, var(--glow-primary) 0%, transparent 70%)',
          top: '-15%',
          right: '-10%',
        }}
      />
      {/* Secondary Luminous Aurora (Bottom Left) */}
      <div
        className="absolute w-[550px] h-[550px] rounded-full opacity-50"
        style={{
          background: 'radial-gradient(circle, var(--glow-secondary) 0%, transparent 70%)',
          bottom: '-10%',
          left: '-10%',
        }}
      />
      {/* Center Subtle Accent Mesh Aura */}
      <div
        className="absolute w-[400px] h-[400px] rounded-full opacity-30 pointer-events-none"
        style={{
          background: 'radial-gradient(circle, color-mix(in srgb, var(--accent-primary) 20%, transparent) 0%, transparent 75%)',
          top: '35%',
          left: '45%',
          transform: 'translate(-50%, -50%)',
        }}
      />
    </div>
  );
}

