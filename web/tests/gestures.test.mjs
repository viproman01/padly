import test from 'node:test';
import assert from 'node:assert/strict';
import { GestureTracker } from '../src/gestures.js';

const point = (id, x, y = 0) => ({ id, x, y });
function setup() {
  const actions = [];
  return { actions, tracker: new GestureTracker(action => actions.push(action)) };
}

test('short one-finger tap is a left click', () => {
  const { tracker, actions } = setup();
  tracker.update('start', [point(1, 20)], 0);
  tracker.update('end', [], 90);
  assert.deepEqual(actions, [{ type: 'tap', button: 0 }]);
});

test('two fingers lifted separately produce exactly one right click', () => {
  const { tracker, actions } = setup();
  tracker.update('start', [point(1, 20)], 0);
  tracker.update('start', [point(1, 20), point(2, 80)], 25);
  tracker.update('end', [point(2, 80)], 80);
  tracker.update('end', [], 100);
  assert.deepEqual(actions, [{ type: 'tap', button: 1 }]);
});

test('one-finger movement preserves deltas and suppresses the tap', () => {
  const { tracker, actions } = setup();
  tracker.update('start', [point(1, 0)], 0);
  tracker.update('move', [point(1, 10, 4)], 10);
  tracker.update('move', [point(1, 16, 7)], 20);
  tracker.update('end', [], 50);
  assert.deepEqual(actions, [{ type: 'move', dx: 10, dy: 4 }, { type: 'move', dx: 6, dy: 3 }]);
});

test('two-finger scroll accumulates its threshold motion and never clicks', () => {
  const { tracker, actions } = setup();
  tracker.update('start', [point(1, 0), point(2, 50)], 0);
  tracker.update('move', [point(1, 0, 2), point(2, 50, 2)], 10);
  assert.deepEqual(actions, []);
  tracker.update('move', [point(1, 0, 7), point(2, 50, 7)], 20);
  tracker.update('move', [point(1, 2, 10), point(2, 52, 10)], 30);
  tracker.update('end', [], 70);
  assert.deepEqual(actions, [{ type: 'scroll', dx: 0, dy: 7 }, { type: 'scroll', dx: 2, dy: 3 }]);
});

test('adding and removing fingers rebases without cursor jumps', () => {
  const { tracker, actions } = setup();
  tracker.update('start', [point(1, 0)], 0);
  tracker.update('move', [point(1, 12)], 10);
  tracker.update('start', [point(1, 12), point(2, 180)], 20);
  tracker.update('end', [point(2, 180)], 30);
  tracker.update('move', [point(2, 184)], 40);
  tracker.update('end', [], 80);
  assert.deepEqual(actions, [{ type: 'move', dx: 12, dy: 0 }, { type: 'move', dx: 4, dy: 0 }]);
});

test('cancelled gestures cannot click and the next gesture starts cleanly', () => {
  const { tracker, actions } = setup();
  tracker.update('start', [point(1, 0)], 0);
  tracker.update('cancel', [], 10);
  tracker.update('end', [], 20);
  tracker.update('move', [point(1, 20)], 30);
  tracker.update('start', [point(2, 30)], 100);
  tracker.update('end', [], 140);
  assert.deepEqual(actions, [{ type: 'tap', button: 0 }]);
});

test('long press, three fingers, and opposing movement do not click', () => {
  const { tracker, actions } = setup();
  tracker.update('start', [point(1, 0)], 0);
  tracker.update('end', [], 400);
  tracker.update('start', [point(1, 0), point(2, 50), point(3, 90)], 500);
  tracker.update('end', [], 550);
  tracker.update('start', [point(1, 0), point(2, 50)], 600);
  tracker.update('move', [point(1, -20), point(2, 70)], 650);
  tracker.update('end', [], 700);
  assert.deepEqual(actions, []);
});

test('unannounced pointer identity changes establish a new baseline', () => {
  const { tracker, actions } = setup();
  tracker.update('start', [point(1, 0)], 0);
  tracker.update('move', [point(2, 150)], 20);
  tracker.update('move', [point(2, 165)], 30);
  tracker.update('end', [], 100);
  assert.deepEqual(actions, [{ type: 'move', dx: 15, dy: 0 }]);
});
