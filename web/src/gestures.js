/** Pure touch classifier. Points contain { id, x, y } in CSS pixels. */
export class GestureTracker {
  constructor(emit, { tapSlop = 9, tapTimeout = 280, scrollSlop = 4 } = {}) {
    this.emit = emit;
    this.tapSlop = tapSlop;
    this.tapTimeout = tapTimeout;
    this.scrollSlop = scrollSlop;
    this.reset();
  }

  reset() {
    this.active = false;
    this.points = new Map();
    this.origins = new Map();
    this.maxFingers = 0;
    this.consumed = false;
    this.travel = 0;
    this.scrollX = 0;
    this.scrollY = 0;
    this.scrolling = false;
  }

  /** phase is start, move, end, or cancel; points are all remaining touches. */
  update(phase, points, time = Date.now()) {
    if (phase === 'cancel') {
      this.reset();
      return;
    }
    const next = new Map(points.map(point => [point.id, point]));
    if (!this.active) {
      if (phase !== 'start' || next.size === 0) return;
      this.active = true;
      this.startedAt = time;
    }
    this.maxFingers = Math.max(this.maxFingers, next.size);
    if (next.size > 2) this.consumed = true;
    for (const [id, point] of next) {
      if (!this.origins.has(id)) this.origins.set(id, point);
      const origin = this.origins.get(id);
      if (Math.hypot(point.x - origin.x, point.y - origin.y) >= this.tapSlop) this.consumed = true;
    }

    const sameSet = next.size === this.points.size && [...next.keys()].every(id => this.points.has(id));
    if (phase === 'move' && sameSet && next.size > 0) {
      let dx = 0;
      let dy = 0;
      let individualTravel = 0;
      for (const [id, point] of next) {
        const previous = this.points.get(id);
        dx += point.x - previous.x;
        dy += point.y - previous.y;
        individualTravel += Math.hypot(point.x - previous.x, point.y - previous.y);
      }
      dx /= next.size;
      dy /= next.size;
      this.travel += individualTravel / next.size;
      if (this.travel >= this.tapSlop) this.consumed = true;
      if (next.size === 1 && (dx || dy)) {
        this.emit({ type: 'move', dx, dy });
      } else if (next.size === 2) {
        this.scrollX += dx;
        this.scrollY += dy;
        if (this.scrolling || Math.hypot(this.scrollX, this.scrollY) >= this.scrollSlop) {
          this.scrolling = true;
          this.consumed = true;
          if (this.scrollX || this.scrollY) this.emit({ type: 'scroll', dx: this.scrollX, dy: this.scrollY });
          this.scrollX = 0;
          this.scrollY = 0;
        }
      }
    } else if (!sameSet) {
      // Finger changes only establish a new baseline, never a cursor delta.
      this.scrollX = 0;
      this.scrollY = 0;
      this.scrolling = false;
    }
    this.points = next;
    if (next.size === 0) {
      if (phase === 'end' && !this.consumed && time - this.startedAt <= this.tapTimeout) {
        if (this.maxFingers === 1 || this.maxFingers === 2) {
          this.emit({ type: 'tap', button: this.maxFingers === 1 ? 0 : 1 });
        }
      }
      this.reset();
    }
  }
}
