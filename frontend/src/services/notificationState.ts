import type { Notification } from '../types';

/** A retry updates an existing item; ordering is independent of delivery order. */
export function mergeNotifications(current: Notification[], incoming: Notification[]): Notification[] {
  const items = new Map(current.map(item => [item.id, item]));
  for (const item of incoming) items.set(item.id, { ...item, read: Boolean(items.get(item.id)?.read || item.read) });
  return [...items.values()].sort((a, b) => b.id - a.id);
}
