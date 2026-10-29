import Foundation

struct ContinuousWearState: Equatable {
    /// First armed `MASK_ON` of this episode. Nil when the person is not in a wear episode.
    var startedAt: Date?
    /// The 12-hour reminder already fired for this episode.
    var reminded: Bool = false
}

/// Continuous mask-on time while guard is armed.
///
/// The lie-down minute passes `armed: false` and does not start the clock.
/// `MASK_ON` while armed starts the episode or keeps the same start.
/// Votes are only `MASK_ON` / `MASK_OFF` / `NO_PERSON` / `UNCERTAIN`.
/// There is no uncertain-with-wear state, so `UNCERTAIN` neither starts nor clears an episode.
/// `MASK_OFF` and `NO_PERSON` clear it. The caller also clears on stop-guard.
/// At 12 hours a later `MASK_ON` reminds once per episode. Guard is not stopped.
enum ContinuousWearPolicy {
    static let remindAfter: TimeInterval = 12 * 60 * 60

    enum Effect: Equatable {
        case none
        case remind(wornFor: TimeInterval)
    }

    static func observe(
        _ state: ContinuousWearState,
        vote: FrameVote,
        armed: Bool,
        now: Date
    ) -> (ContinuousWearState, Effect) {
        guard armed else { return (state, .none) }
        switch vote {
        case .maskOff, .noPerson:
            return (ContinuousWearState(), .none)
        case .uncertain:
            return (state, .none)
        case .maskOn:
            var next = state
            if next.startedAt == nil {
                next.startedAt = now
                next.reminded = false
            }
            guard let started = next.startedAt, !next.reminded else {
                return (next, .none)
            }
            let worn = now.timeIntervalSince(started)
            if worn >= remindAfter {
                next.reminded = true
                return (next, .remind(wornFor: worn))
            }
            return (next, .none)
        }
    }

    /// Debug invariant check. Safe to call once at launch in Debug builds.
    static func selfCheck() {
        let t0 = Date(timeIntervalSince1970: 1_700_000_000)
        var state = ContinuousWearState()
        var effect = Effect.none

        (state, effect) = observe(state, vote: .maskOn, armed: false, now: t0)
        precondition(state.startedAt == nil && effect == .none, "settle must not start wear")

        (state, effect) = observe(state, vote: .maskOn, armed: true, now: t0)
        precondition(state.startedAt == t0 && effect == .none && !state.reminded, "first on starts")

        let almost = t0.addingTimeInterval(remindAfter - 1)
        (state, effect) = observe(state, vote: .maskOn, armed: true, now: almost)
        precondition(effect == .none && !state.reminded, "just under 12h")

        let hit = t0.addingTimeInterval(remindAfter)
        (state, effect) = observe(state, vote: .maskOn, armed: true, now: hit)
        guard case .remind(let worn) = effect else {
            preconditionFailure("12h should remind")
        }
        precondition(worn >= remindAfter && worn < remindAfter + 1 && state.reminded, "remind once")

        (state, effect) = observe(state, vote: .maskOn, armed: true, now: hit.addingTimeInterval(30))
        precondition(effect == .none && state.reminded && state.startedAt == t0, "no repeat")

        (state, effect) = observe(state, vote: .uncertain, armed: true, now: hit.addingTimeInterval(60))
        precondition(effect == .none && state.startedAt == t0, "uncertain keeps episode")

        (state, effect) = observe(state, vote: .maskOff, armed: true, now: hit.addingTimeInterval(90))
        precondition(state == ContinuousWearState() && effect == .none, "off clears")

        (state, effect) = observe(state, vote: .maskOn, armed: true, now: hit)
        (state, effect) = observe(state, vote: .noPerson, armed: true, now: hit.addingTimeInterval(10))
        precondition(state.startedAt == nil, "no person clears")

        (state, effect) = observe(ContinuousWearState(), vote: .uncertain, armed: true, now: t0)
        precondition(state.startedAt == nil, "uncertain does not start")

        var running = ContinuousWearState(startedAt: t0, reminded: false)
        (running, effect) = observe(running, vote: .maskOff, armed: false, now: hit)
        precondition(running.startedAt == t0, "unarmed vote does not clear")
        (running, effect) = observe(running, vote: .uncertain, armed: true, now: hit)
        precondition(effect == .none && !running.reminded, "uncertain past 12h does not alert")
        (running, effect) = observe(running, vote: .maskOn, armed: true, now: hit.addingTimeInterval(8))
        guard case .remind = effect else {
            preconditionFailure("next mask-on after 12h alerts")
        }
        precondition(running.reminded && running.startedAt == t0, "same episode")
    }
}
