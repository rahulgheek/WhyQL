"use client";

import Link from "next/link";
import { useRef, useState, type ReactNode } from "react";

/**
 * Landing-page motion primitives. Client-side by design (mouse tracking),
 * used only by the dark marketing page — the app shell stays untouched.
 */

/**
 * Card with a cursor-following spotlight: a soft radial light that tracks the
 * mouse and fades in on hover, plus a gentle lift. The classic "glowing
 * glass" feel of modern AI dashboards.
 */
export function SpotlightCard({
  className = "",
  children,
}: {
  className?: string;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ x: number; y: number } | null>(null);

  return (
    <div
      ref={ref}
      onMouseMove={(e) => {
        const rect = ref.current?.getBoundingClientRect();
        if (rect) {
          setPos({ x: e.clientX - rect.left, y: e.clientY - rect.top });
        }
      }}
      onMouseLeave={() => setPos(null)}
      className={`group relative transform-gpu transition-[transform,border-color,box-shadow] duration-300 ease-out hover:-translate-y-1 hover:border-indigo-400/40 hover:shadow-[0_16px_48px_-16px_rgba(99,102,241,0.35)] ${className}`}
    >
      {/* cursor spotlight */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 z-0 opacity-0 transition-opacity duration-300 group-hover:opacity-100"
        style={{
          background: pos
            ? `radial-gradient(260px circle at ${pos.x}px ${pos.y}px, rgba(129,140,248,0.14), transparent 65%)`
            : undefined,
        }}
      />
      <div className="relative z-[1] h-full">{children}</div>
    </div>
  );
}

/**
 * 3D tilt frame: the content leans toward the cursor inside a perspective
 * transform, floating higher on hover. Springs back flat on mouse-leave.
 */
export function TiltFrame({ children }: { children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const [tilt, setTilt] = useState({ rx: 0, ry: 0, lift: 0 });
  const [springs, setSprings] = useState(true);

  return (
    <div
      ref={ref}
      onMouseMove={(e) => {
        const rect = ref.current?.getBoundingClientRect();
        if (!rect) return;
        // Offset from center, normalized to [-0.5, 0.5].
        const nx = (e.clientX - rect.left) / rect.width - 0.5;
        const ny = (e.clientY - rect.top) / rect.height - 0.5;
        setSprings(false);
        setTilt({ rx: -ny * 9, ry: nx * 11, lift: 6 });
      }}
      onMouseLeave={() => {
        setSprings(true);
        setTilt({ rx: 0, ry: 0, lift: 0 });
      }}
      className="transform-gpu [perspective:1400px]"
    >
      <div
        style={{
          transform: `rotateX(${tilt.rx}deg) rotateY(${tilt.ry}deg) translateZ(${tilt.lift}px)`,
          transition: springs
            ? "transform 600ms cubic-bezier(0.22, 1, 0.36, 1)"
            : "transform 80ms linear",
          willChange: "transform",
        }}
        className="transform-gpu"
      >
        {children}
      </div>
    </div>
  );
}

/**
 * Gradient button with a light sheen sweeping across on hover, plus a
 * tactile press. Use as the inner content of CTA links.
 */
export function SheenButton({
  href,
  children,
  variant,
}: {
  href: string;
  children: ReactNode;
  variant: "gradient" | "ghost";
}) {
  const base =
    "group relative inline-flex items-center gap-2 overflow-hidden rounded-xl px-6 py-3 text-sm font-semibold transform-gpu transition duration-200 ease-out hover:scale-[1.03] active:scale-95";
  const cls =
    variant === "gradient"
      ? `${base} bg-gradient-to-r from-indigo-400 via-violet-400 to-fuchsia-400 text-slate-950 shadow-lg shadow-indigo-500/30 hover:shadow-indigo-500/50 hover:brightness-110`
      : `${base} border border-white/15 bg-white/5 text-slate-200 hover:border-white/30 hover:bg-white/10 hover:shadow-lg hover:shadow-white/5`;
  return (
    <Link href={href} className={cls}>
      <span
        aria-hidden
        className="absolute inset-0 -translate-x-[120%] bg-gradient-to-r from-transparent via-white/25 to-transparent transition-transform duration-700 ease-out group-hover:translate-x-[120%]"
      />
      <span className="relative z-[1]">{children}</span>
    </Link>
  );
}
