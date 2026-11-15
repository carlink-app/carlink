//! Sender-side rate control that ignores random loss.
//!
//! QUIC's congestion controller treats Wi-Fi's random loss as congestion and
//! collapses (see spike/docs/RESULTS.md). Video needs a different signal:
//! queueing. When the link can't carry the bitrate, frames back up in our
//! send queue and RTT climbs above its floor; random loss does neither.
//!
//! Wi-Fi also has short busy periods (interference, retries: 30-150 ms) that
//! stall everything whatever the bitrate. Reacting to those would pin the
//! rate at the floor, so only sustained queueing counts: most of the last
//! second's ticks congested. Then back off by a quarter; creep up after a
//! clean second, but only while video is actually flowing: an idle screen
//! sends almost nothing, so it says nothing about what the link can carry,
//! and the next burst of motion would hit it at full rate.
//!
//! The output is a level in permille of the codec's maximum bitrate; the
//! engine maps it to bits per second for the codec in use.

use std::time::{Duration, Instant};

pub const MIN_LEVEL: u32 = 150;
pub const MAX_LEVEL: u32 = 1000;
pub const START_LEVEL: u32 = 800;

/// RTT above the floor that counts as queueing.
const RTT_SLACK: Duration = Duration::from_millis(30);
/// Minimum RTT is tracked over this window, so a route change can raise it.
const FLOOR_WINDOW: Duration = Duration::from_secs(10);
const DECREASE_EVERY: Duration = Duration::from_millis(600);
/// Frames per 200 ms tick that count as "video is flowing" (30 fps). A
/// static screen only repeats a frame every 100 ms.
const MOVING_FRAMES: u32 = 6;
/// Ticks remembered (5 x 200 ms = 1 s) and how many must be congested.
const HISTORY: u32 = 5;
const SUSTAINED: u32 = 3;
const INCREASE_AFTER_CLEAN: Duration = Duration::from_secs(1);

#[derive(Debug)]
pub struct RateControl {
    level: u32,
    floor: Option<(Duration, Instant)>,
    /// Floor candidate for the next window.
    next_floor: Option<Duration>,
    last_decrease: Option<Instant>,
    clean_since: Instant,
    last_increase: Instant,
    /// Congested flags of the last HISTORY ticks, newest in bit 0.
    history: u8,
}

/// What the sender saw since the last tick.
#[derive(Debug, Clone, Copy)]
pub struct Sample {
    pub rtt: Duration,
    /// Times the send queue overflowed (see server::Backpressure).
    pub dropped: u32,
    /// Frames queued for sending since the last tick.
    pub sent: u32,
    /// Frames waiting in the send queue right now.
    pub queued: usize,
    pub queue_capacity: usize,
}

impl RateControl {
    pub fn new(now: Instant) -> Self {
        RateControl {
            level: START_LEVEL,
            floor: None,
            next_floor: None,
            last_decrease: None,
            clean_since: now,
            last_increase: now,
            history: 0,
        }
    }

    /// Tests that need room to climb start below START_LEVEL.
    #[cfg(test)]
    fn starting_at(now: Instant, level: u32) -> Self {
        RateControl { level, ..Self::new(now) }
    }

    pub fn level(&self) -> u32 {
        self.level
    }

    fn rtt_floor(&mut self, rtt: Duration, now: Instant) -> Duration {
        self.next_floor = Some(self.next_floor.map_or(rtt, |f| f.min(rtt)));
        match self.floor {
            Some((f, since)) if now.duration_since(since) < FLOOR_WINDOW => {
                let f = f.min(rtt);
                self.floor = Some((f, since));
                f
            }
            _ => {
                // Start a new window from the best RTT seen in the last one.
                let f = self.next_floor.take().unwrap_or(rtt);
                self.floor = Some((f, now));
                f
            }
        }
    }

    /// Feed one sample; returns the new level if it changed.
    pub fn tick(&mut self, now: Instant, s: Sample) -> Option<u32> {
        let floor = self.rtt_floor(s.rtt, now);
        let congested = s.dropped > 0 || s.queued * 2 >= s.queue_capacity.max(1) || s.rtt > floor + RTT_SLACK;
        self.history = ((self.history << 1) | congested as u8) & ((1 << HISTORY) - 1);
        let old = self.level;
        if congested {
            self.clean_since = now;
        }
        if self.history.count_ones() >= SUSTAINED {
            let due = self.last_decrease.is_none_or(|t| now.duration_since(t) >= DECREASE_EVERY);
            if due {
                self.last_decrease = Some(now);
                self.level = (self.level * 3 / 4).max(MIN_LEVEL);
            }
        } else if !congested
            && s.sent >= MOVING_FRAMES
            && now.duration_since(self.clean_since) >= INCREASE_AFTER_CLEAN
            && now.duration_since(self.last_increase) >= INCREASE_AFTER_CLEAN
        {
            self.last_increase = now;
            self.level = (self.level + self.level / 8 + 20).min(MAX_LEVEL);
        }
        (self.level != old).then_some(self.level)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MS: Duration = Duration::from_millis(1);

    fn clean(rtt_ms: u64) -> Sample {
        Sample { rtt: Duration::from_millis(rtt_ms), dropped: 0, sent: 12, queued: 0, queue_capacity: 6 }
    }

    /// Run ticks every 200 ms for `secs` seconds.
    fn run(rc: &mut RateControl, t: &mut Instant, secs: u64, s: Sample) {
        for _ in 0..secs * 5 {
            *t += 200 * MS;
            rc.tick(*t, s);
        }
    }

    #[test]
    fn climbs_to_max_on_a_clean_path() {
        let mut t = Instant::now();
        let mut rc = RateControl::new(t);
        run(&mut rc, &mut t, 30, clean(5));
        assert_eq!(rc.level(), MAX_LEVEL);
    }

    #[test]
    fn backs_off_on_drops_and_holds_the_floor() {
        let mut t = Instant::now();
        let mut rc = RateControl::new(t);
        let bad = Sample { dropped: 2, ..clean(5) };
        // Needs SUSTAINED congested ticks first.
        for _ in 0..SUSTAINED - 1 {
            t += 200 * MS;
            assert_eq!(rc.tick(t, bad), None);
        }
        t += 200 * MS;
        assert_eq!(rc.tick(t, bad), Some(START_LEVEL * 3 / 4));
        // Next decrease waits DECREASE_EVERY.
        t += 200 * MS;
        assert_eq!(rc.tick(t, bad), None);
        run(&mut rc, &mut t, 10, bad);
        assert_eq!(rc.level(), MIN_LEVEL);
    }

    #[test]
    fn rtt_growth_counts_as_congestion_but_jitter_within_slack_does_not() {
        let mut t = Instant::now();
        let mut rc = RateControl::starting_at(t, 400);
        run(&mut rc, &mut t, 2, clean(5));
        let before = rc.level();
        // +20 ms: within slack, still increasing.
        run(&mut rc, &mut t, 2, clean(25));
        assert!(rc.level() > before);
        // +60 ms over the floor, sustained: queueing.
        let high = rc.level();
        run(&mut rc, &mut t, 1, clean(65));
        assert!(rc.level() < high);
    }

    #[test]
    fn half_full_queue_is_congestion() {
        let mut t = Instant::now();
        let mut rc = RateControl::new(t);
        run(&mut rc, &mut t, 1, Sample { queued: 3, ..clean(5) });
        assert!(rc.level() < START_LEVEL);
    }

    #[test]
    fn short_wifi_stalls_do_not_reduce() {
        // A 100-200 ms busy period once a second: one or two bad ticks out of five.
        let mut t = Instant::now();
        let mut rc = RateControl::new(t);
        let stall = Sample { rtt: 150 * MS, dropped: 3, sent: 4, queued: 6, queue_capacity: 6 };
        for i in 0..50 {
            t += 200 * MS;
            rc.tick(t, if i % 5 == 0 { stall } else { clean(5) });
        }
        assert!(rc.level() >= START_LEVEL, "level {}", rc.level());
    }

    #[test]
    fn idle_screen_does_not_raise_the_level() {
        let mut t = Instant::now();
        let mut rc = RateControl::new(t);
        run(&mut rc, &mut t, 10, Sample { sent: 2, ..clean(5) });
        assert_eq!(rc.level(), START_LEVEL);
    }

    #[test]
    fn random_loss_alone_does_not_reduce() {
        // Loss shows up as neither drops in our queue nor RTT growth when the
        // link has spare capacity: the level keeps rising.
        let mut t = Instant::now();
        let mut rc = RateControl::new(t);
        run(&mut rc, &mut t, 5, clean(8));
        assert!(rc.level() > START_LEVEL);
    }

    #[test]
    fn increases_at_most_once_per_clean_second_by_an_eighth_plus_20() {
        let mut t = Instant::now();
        let mut rc = RateControl::starting_at(t, 400);
        let mut changes = Vec::new();
        for i in 1..=10 {
            t += 200 * MS;
            if let Some(l) = rc.tick(t, clean(5)) {
                changes.push((i, l));
            }
        }
        let first = 400 + 400 / 8 + 20;
        assert_eq!(changes, vec![(5, first), (10, first + first / 8 + 20)]);
    }

    #[test]
    fn a_single_congested_tick_delays_the_next_increase() {
        let mut t = Instant::now();
        let mut rc = RateControl::new(t);
        for _ in 0..4 {
            t += 200 * MS;
            rc.tick(t, clean(5));
        }
        t += 200 * MS;
        assert_eq!(rc.tick(t, Sample { dropped: 1, ..clean(5) }), None); // not sustained: no decrease
        // Clean again, but the clean second restarts from the bad tick.
        for _ in 0..4 {
            t += 200 * MS;
            assert_eq!(rc.tick(t, clean(5)), None);
        }
        t += 200 * MS;
        assert!(rc.tick(t, clean(5)).is_some());
    }

    #[test]
    fn keeps_backing_off_while_most_of_the_last_second_was_congested() {
        // The current tick being clean doesn't matter while 3 of the last 5
        // were congested: the next due decrease still happens.
        let mut t = Instant::now();
        let mut rc = RateControl::new(t);
        let bad = Sample { dropped: 1, ..clean(5) };
        let mut changes = Vec::new();
        for s in [bad, bad, bad, bad, clean(5), clean(5), clean(5)] {
            t += 200 * MS;
            changes.push(rc.tick(t, s));
        }
        let first = START_LEVEL * 3 / 4;
        // 600 ms: first decrease. 1200 ms: due again, history 3/5 although
        // this tick was clean. 1400 ms: 2/5, no decrease, and not a clean second yet.
        assert_eq!(changes, vec![None, None, Some(first), None, None, Some(first * 3 / 4), None]);
    }

    #[test]
    fn full_queue_with_zero_capacity_is_not_congestion() {
        // queue_capacity.max(1) guards a zero-capacity probe.
        let mut t = Instant::now();
        let mut rc = RateControl::new(t);
        run(&mut rc, &mut t, 2, Sample { queued: 0, queue_capacity: 0, ..clean(5) });
        assert!(rc.level() > START_LEVEL);
    }

    #[test]
    fn floor_drops_immediately_when_rtt_improves() {
        let mut t = Instant::now();
        let mut rc = RateControl::new(t);
        run(&mut rc, &mut t, 2, clean(50));
        // One 10 ms sample lowers the floor at once: 50 ms is now queueing.
        t += 200 * MS;
        rc.tick(t, clean(10));
        let before = rc.level();
        run(&mut rc, &mut t, 1, clean(50));
        assert!(rc.level() < before, "level {} before {before}", rc.level());
    }

    #[test]
    fn floor_follows_a_route_change_upward() {
        let mut t = Instant::now();
        let mut rc = RateControl::new(t);
        run(&mut rc, &mut t, 3, clean(5));
        // Path now has 50 ms base RTT (e.g. a different link). It's congestion
        // at first, but after the floor windows roll over it's the new normal.
        run(&mut rc, &mut t, 25, clean(50));
        let settled = rc.level();
        run(&mut rc, &mut t, 5, clean(50));
        assert!(rc.level() > settled || rc.level() == MAX_LEVEL);
    }
}
