import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mergeNotifications } from '../src/services/notificationState.ts';

test('duplicate and out-of-order delivery keeps one row per notification', () => {
  const result = mergeNotifications([{ id: 3, read: false }, { id: 1, read: false }], [{ id: 2, read: false }, { id: 3, read: true }]);
  assert.deepEqual(result.map(item => item.id), [3, 2, 1]);
  assert.equal(result[0].read, true);
  assert.equal(mergeNotifications(result, [{ id: 3, read: false }])[0].read, true);
});

test('a reconnect snapshot merges with already loaded history without losing older rows', () => {
  const current = Array.from({ length: 65 }, (_, index) => ({ id: index + 1, read: false }));
  const incoming = Array.from({ length: 50 }, (_, index) => ({ id: index + 31, read: true }));
  const result = mergeNotifications(current, incoming);
  assert.equal(result.length, 80);
  assert.equal(result[0].id, 80);
  assert.equal(result.at(-1).id, 1);
});
