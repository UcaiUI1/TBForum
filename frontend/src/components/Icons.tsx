// 轻量内联 SVG 图标（stroke=currentColor，随文字颜色）
import React from 'react';

type P = { size?: number; className?: string };

const base = (p: P) => ({
  width: p.size ?? 16,
  height: p.size ?? 16,
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 2,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
  className: p.className,
  'aria-hidden': true,
});

export const IconSearch = (p: P) => (<svg {...base(p)}><circle cx="11" cy="11" r="7" /><line x1="21" y1="21" x2="16.65" y2="16.65" /></svg>);
export const IconBell = (p: P) => (<svg {...base(p)}><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" /><path d="M13.73 21a2 2 0 0 1-3.46 0" /></svg>);
export const IconUp = (p: P) => (<svg {...base(p)}><polyline points="18 15 12 9 6 15" /></svg>);
export const IconComment = (p: P) => (<svg {...base(p)}><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" /></svg>);
export const IconView = (p: P) => (<svg {...base(p)}><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" /><circle cx="12" cy="12" r="3" /></svg>);
export const IconStar = (p: P) => (<svg {...base(p)}><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" /></svg>);
export const IconLock = (p: P) => (<svg {...base(p)}><rect x="3" y="11" width="18" height="11" rx="2" /><path d="M7 11V7a5 5 0 0 1 10 0v4" /></svg>);
export const IconPin = (p: P) => (<svg {...base(p)}><line x1="12" y1="17" x2="12" y2="22" /><path d="M5 17h14l-2-4V7H7v6z" /></svg>);
export const IconReport = (p: P) => (<svg {...base(p)}><path d="M4 22V4a1 1 0 0 1 .5-.9A1 1 0 0 1 6 3.6L12 6l6-2.4a1 1 0 0 1 1 .9v10" /></svg>);
export const IconMenu = (p: P) => (<svg {...base(p)}><line x1="3" y1="6" x2="21" y2="6" /><line x1="3" y1="12" x2="21" y2="12" /><line x1="3" y1="18" x2="21" y2="18" /></svg>);
export const IconSun = (p: P) => (<svg {...base(p)}><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" /></svg>);
export const IconMoon = (p: P) => (<svg {...base(p)}><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" /></svg>);
export const IconRss = (p: P) => (<svg {...base(p)}><path d="M4 11a9 9 0 0 1 9 9M4 4a16 16 0 0 1 16 16" /><circle cx="5" cy="19" r="1" /></svg>);
export const IconCircle = (p: P) => (<svg {...base(p)}><circle cx="12" cy="12" r="8" fill="currentColor" stroke="none" /></svg>);
export const IconChevron = (p: P) => (<svg {...base(p)}><polyline points="6 9 12 15 18 9" /></svg>);
export const IconUserPlus = (p: P) => (<svg {...base(p)}><path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" /><circle cx="8.5" cy="7" r="4" /><line x1="20" y1="8" x2="20" y2="14" /><line x1="23" y1="11" x2="17" y2="11" /></svg>);

export const NotifIcon = ({ kind, size }: { kind: string; size?: number }) => {
  switch (kind) {
    case 'REPLY': return <IconComment size={size} />;
    case 'UPVOTE': return <IconUp size={size} />;
    case 'MENTION': return <span className="notif-mention">@</span>;
    case 'FEATURED': return <IconStar size={size} />;
    case 'FOLLOW': return <IconUserPlus size={size} />;
    default: return <span className="notif-mention">…</span>;
  }
};
