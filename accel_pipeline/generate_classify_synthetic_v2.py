"""
Synthetic dataset v2 -- physical vs. psychological stress, plus a motion-gated
classifier that tells them apart.

WHY this exists (from the design discussion with Nami, 2026-10):
  HR/HRV and EDA both move for physical exertion AND psychological stress -- they
  don't tell those two apart on their own. The accelerometer is what lets you tell
  them apart (moving = possibly physical, not moving = possibly psychological).
  Skin temperature actually moves in OPPOSITE directions for the two: vasodilation
  (exercise) raises it, vasoconstriction (psychological stress) drops it -- so it's
  a second, independent cross-check, not just "defendable," actually useful here.

Same zero-evidentiary-value caveat as generate_synthetic_cortisol_dataset.py: every
number here is made up by this script, for demonstrating the pipeline + classifier
methodology before real TSST + real exercise-control data exists. The real answer to
"does physical exertion also raise cortisol" comes from the exercise-endocrinology
literature, not from this script (see the chat answer that goes with this file).

Session types generated (each a distinct synthetic "participant-session"):
  rest           -- baseline only, no stressor. Negative control: cortisol should
                     NOT meaningfully rise.
  physical       -- baseline -> physical exertion (walking/exercise-like motion,
                     higher HR, EDA up a little, skin temp UP, high accel) -> recovery.
  psychological  -- baseline -> psychological stressor (like the real presentation
                     event: HR up, EDA up more, skin temp DOWN, motion flat) -> recovery.
  mixed          -- baseline -> physical phase -> brief rest gap -> psychological
                     phase -> recovery, all in one session. This is the one that
                     actually tests whether the classifier can separate two
                     different causes of arousal within a single continuous session,
                     which is the real-world case (you don't get a label telling you
                     which kind of "stress" it was).
  daily_baseline -- NO stressor at all. Several quiet periods with brief (45-90s),
                     spontaneous, no-motion EDA-only bursts scattered through (Case A)
                     -- added because Nami observed a real EDA spike on her own watch
                     data while asleep. Ground truth is "rest" throughout.
  sustained_eda_drift -- NO stressor at all, like daily_baseline, but the EDA-only
                     elevation is SUSTAINED for 10-20 minutes rather than brief (Case
                     B -- added 2026-10, CRITICAL). This is the actual shape of what
                     Nami's real Oct 6 recording showed: EDA sat several uS above its
                     own baseline for the entire ~7.25h of confirmed sleep, no motion,
                     no HR change. Unlike Case A's brief bursts (which mostly don't
                     clear MIN_PERSIST_WINDOWS and were already handled), this easily
                     clears it -- it's what exposed the OR-logic false-positive bug
                     that HR_CORROBORATION_FRAC (see its own comment) now fixes.
                     Ground truth is "rest" throughout.

The report (_print_report / plot_v2_summary_dashboard.py) shows, separately for Case A
and Case B, how often the classifier calls one of these windows "psychological" (false
stress call) vs. the new "non_stress_arousal" label -- and, as the honesty check on the
other side of that fix, how much recall it costs on genuinely-psychological sessions
whose HR response happens to be weak (Case C -- see PSYCH_HR_BLUNTED_PROB).

Cortisol: each stressor phase contributes its own rise-then-decay bump (log-normal
shaped, peaking 15-40 min after that phase's onset -- widened from a single ~20-25 min
assumption since real peak timing varies; still anchored to the same
CORTISOL_LAG_MIN_DEFAULT idea as generate_synthetic_cortisol_dataset.py). A "physical"
phase and a "psychological" phase both get a cortisol-raising effect in this script --
that is a DESIGN CHOICE for demonstrating the pipeline, not a claim about which raises
it more; see the chat discussion for the real literature answer. Not every stressor
event responds, either: NON_RESPONDER_PROB_PHYS/PSYCH give each physical/psychological
phase a chance of a blunted cortisol curve even though HR/EDA/motion clearly moved --
real HPA-axis "non-response" is a documented phenomenon, and it's deliberately made
more common for physical phases (mild/moderate exercise often doesn't raise cortisol
much; it tends to need to be fairly vigorous or prolonged).
daily_baseline sessions get no stressor_events at all, so their cortisol stays at
participant_baseline +/- noise throughout -- a second negative control alongside "rest".

Classifier (Stage 1, rule-based -- deliberately simple and transparent so it's easy
to defend in a methods chapter; an ML classifier trained on top of this is future
work, not done here -- see compare_classifiers.py for how a Random Forest trained on
the SAME ground truth compares):
  1. Compute each session's own baseline HR/EDA from its first ~2 minutes (the
     assumed-calm start of every session, same idea as the "measure baseline EDA
     first" design point).
  2. elevated = HR or EDA meaningfully above that session's own baseline.
  3. PERSISTENT elevated = elevated for >=MIN_PERSIST_WINDOWS consecutive 30s windows
     (>=90s). A single elevated window on its own does NOT count -- added because a
     one-window spontaneous EDA blip (see daily_baseline / is_spontaneous_eda_burst
     below) was getting called "psychological" 100% of the time under a single-window
     rule. Real stress/activity sustains itself; a spontaneous blip usually doesn't.
  4. not persistent-elevated          -> predict "rest"
     persistent-elevated + moving     -> predict "physical"
     persistent-elevated + not moving + HR corroborated for the run (see
       HR_CORROBORATION_FRAC)         -> predict "psychological"
     persistent-elevated + not moving + HR NOT corroborated
                                       -> predict "non_stress_arousal"
  "Moving" reuses the pipeline's own existing accel_any_motion_flagged column
  (accel_windowing.py's motion_flag, std > 0.05g) rather than inventing a new
  threshold. Trade-off from step 3: the first window or two of a REAL
  physical/psychological phase will also read as "rest" until persistence builds up
  -- this costs a little sensitivity right at the start of each real event in
  exchange for not reacting to momentary noise.
  Step 4's 4th outcome ("non_stress_arousal") is new as of 2026-10 -- see
  HR_CORROBORATION_FRAC's comment just above _cortisol_bump for the real-data bug it
  fixes (a sustained, no-motion EDA-only elevation -- real sleep, in Nami's case --
  was being called "psychological" on ~90% of its windows) and the Case A/B/C
  synthetic sessions added to measure the fix honestly, both the false-positive
  reduction and the recall it costs.

Usage:
    python generate_classify_synthetic_v2.py --n-per-type 5 --out-dir synth_v2_out
"""

import argparse
import json
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import accel_windowing
import hrv_eda_windowing
import skin_temp_windowing
import merge_windows

RNG_SEED_DEFAULT = 2026
PARTICIPANT_EMAIL = "synthetic@example.com"

ACCEL_RATE_HZ = accel_windowing.NOMINAL_RATE_HZ
ACCEL_COUNTS_PER_G = accel_windowing.COUNTS_PER_G

BASELINE_SEC = 180          # 3 min calm start -- also used as the classifier's own baseline window
GAP_SEC = 120               # rest gap between phases in a "mixed" session

MIN_PERSIST_WINDOWS = 4     # an "elevated" reading must hold for at least this many
                             # CONSECUTIVE 30s windows (>=120s) before the classifier
                             # calls it physical/psychological. A single elevated
                             # window reverts to "rest" instead. Added because a
                             # one-window spontaneous EDA blip (see daily_baseline /
                             # is_spontaneous_eda_burst) was getting called
                             # "psychological" 100% of the time under a single-window
                             # rule -- real stress/activity sustains itself, a
                             # spontaneous blip usually doesn't.
                             #   Picked empirically by sweeping 3/4/5/6: 3 only cut the
                             # false positives from 23/23 to 19/23 (most bursts span
                             # more than one 30s window because the EDA smoothing has
                             # its own decay tail); 4 is the first value that fully
                             # eliminates them (0/23) while psychological accuracy
                             # stays exactly 99.3% (real stress events comfortably
                             # outlast 120s); 5 and 6 don't remove any more false
                             # positives but keep costing real physical/mixed-session
                             # sensitivity (overall accuracy fell from 96.7% at 4 to
                             # 94.4% at 5 and 92.4% at 6). Trade-off that remains even
                             # at 4: the first window or two of a REAL
                             # physical/psychological phase also reads as "rest" until
                             # persistence builds up, and short/low-intensity physical
                             # phases (mostly inside "mixed" sessions) lose some
                             # sensitivity -- mixed-session accuracy dropped from 96.0%
                             # (no persistence check) to 92.7% (at 4).
RECOVERY_SEC = 480          # 8 min recovery, long enough to see cortisol peak+decay
PHYS_SEC_RANGE = (90, 180)
PSYCH_SEC_RANGE = (90, 180)

K_PHYS_CORTISOL = 5.0        # cortisol-rise-per-unit-intensity for a physical phase (if it responds at all)
K_PSYCH_CORTISOL = 6.0       # cortisol-rise-per-unit-intensity for a psychological phase (if it responds at all)

HR_ELEVATED_THRESH_BPM = 6.0   # bpm above a session's own baseline counts as "elevated"
EDA_ELEVATED_THRESH_US = 0.4   # uS above a session's own baseline counts as "elevated"
# Module-level (not just local to _classify) so other scripts, e.g. the report
# generator, can import and display the actual thresholds in use instead of a second,
# hand-typed copy that could drift out of sync.

# Not every stressor produces a meaningful cortisol rise, even when HR/EDA/motion
# clearly show something happened -- this is a real, documented phenomenon (HPA-axis
# "non-response" / blunted cortisol reactivity). It's MORE common for physical
# exertion specifically: real exercise physiology needs fairly vigorous/prolonged
# activity to reliably raise cortisol -- moderate activity often doesn't move it much.
# These probabilities are an assumption for this synthetic generator, not a measured
# rate -- the point is just to make sure "physical/psychological stress happened but
# cortisol barely moved" is a case this dataset actually contains.
NON_RESPONDER_PROB_PHYS = 0.40
NON_RESPONDER_PROB_PSYCH = 0.15
BLUNTED_PEAK_RISE_MEAN = 0.3   # near-baseline bump size for a "non-responder" event
BLUNTED_PEAK_RISE_STD = 0.15

PEAK_MINUTES_RANGE = (15, 40)  # widened from a fixed ~18-27 min -- real cortisol peak
                                # timing varies person to person and stressor to
                                # stressor; 20-30 min is the most commonly cited window
                                # but 30-40 min responses are also reported.

# Real saliva cortisol assays are limited to 5 draws per session (lab/budget
# constraint, decided with Nami) -- down from an earlier 8-sample version of this
# generator. One pre-stressor baseline (-5 min), then spread across the 15-40 min
# peak window and into recovery, so 5 points can still trace the rise-then-decay
# shape: a point right at stressor onset, two across where the peak usually lands,
# and one well into decay.
SAMPLE_OFFSETS_MIN = [-5, 0, 20, 40, 60]

# --- Multimodal corroboration for "psychological" (added 2026-10, real-data bug fix) ---
# Nami's real Oct 6 overnight recording showed EDA sustained several uS above its own
# session baseline for the ENTIRE ~7.25 hours of confirmed sleep (2:39am-9:55am), while
# HR was NEVER elevated above baseline during that same stretch. Checked directly
# against the real merged 60s-window table: of the windows the OLD classifier (elevated
# = HR OR EDA, then motion-gated) called "psychological" during that confirmed-sleep
# stretch, 100% had hr_dev_from_baseline <= HR_ELEVATED_THRESH_BPM and 100% had
# eda_dev_from_baseline > EDA_ELEVATED_THRESH_US -- i.e. EDA ALONE, with zero HR
# corroboration, was driving every single false call (393/437 = 89.9% of that session's
# sleep windows). The OR-logic rule had no way to tell that apart from real
# psychological stress, which both this generator's own design and the literature on
# multimodal ambulatory stress detection treat as co-activating HR and EDA together --
# an isolated sustained tonic EDA elevation is equally consistent with thermoregulation,
# hydration/electrode contact changes, or sleep-stage transitions, none of which are
# stress.
#
# Fix: once a window falls in a persistent-elevated, non-moving run (same persistence
# gate as before), the RUN as a whole -- not each window individually, to avoid
# flickering between labels mid-episode -- is only called "psychological" if HR was
# ALSO elevated (above HR_CORROBORATION_THRESH_BPM, a SEPARATE and much lower bar than
# HR_ELEVATED_THRESH_BPM -- see why below) in at least HR_CORROBORATION_FRAC of that
# run's windows. Otherwise the run is reported as "non_stress_arousal": EDA genuinely
# moved and isn't hidden behind a confident "rest" label, but it is NOT reported as
# stress either.
#
# Why a SEPARATE, lower threshold instead of reusing HR_ELEVATED_THRESH_BPM (6.0bpm):
# the first attempt at this fix did reuse it, and it badly over-corrected -- on this
# generator's own synthetic psychological sessions, hr_dev clears 6.0bpm in only 42.4%
# of genuinely-psychological (non-blunted) windows, because EDA_ELEVATED_THRESH_US
# (0.4uS against a x3.2-intensity coefficient) is a much more sensitive relative bar
# than HR_ELEVATED_THRESH_BPM (6.0bpm against a x180-intensity coefficient) -- real
# low-to-moderate-intensity stress clears the EDA bar long before it clears the HR bar.
# Reusing the same strict bar for corroboration collapsed psychological recall to 45.5%
# even on non-blunted sessions, which is a regression, not a fix.
# Checking the actual distributions instead (not reusing the "is anything elevated at
# all" bar) shows a much cleaner, separate signal: in the real Oct 6 sleep data, only
# 0.9% of confirmed-sleep windows have hr_dev_from_baseline > 0bpm at all (0.7% at
# >1bpm) -- HR is flat or below baseline essentially the whole night. Meanwhile, EVERY
# genuinely-psychological synthetic window -- including the HR-blunted Case C
# sub-population, by construction the hardest case -- has hr_dev_from_baseline > 1bpm
# (100% in both the blunted and non-blunted groups; the blunted group's minimum is
# +1.57bpm). So ">1bpm" cleanly separates "real stress, even a weak cardiac responder"
# from "real sleep" on this data, in a way ">6bpm" does not.
# HR_CORROBORATION_THRESH_BPM = 1.0 was picked from that separation. HR_CORROBORATION_FRAC
# was then swept at 0.0/0.10/0.15/0.25/0.35/0.5/0.65/0.75/1.0 (n=20 sessions/type, 4
# seeds) against Case A (brief burst) and Case B (sustained drift) false-positive
# counts and Case C (HR-blunted) recall. Note 0.0 is degenerate -- "mean >= 0.0" is
# always true, so it's really "corroboration disabled," not "the weakest real check"
# (confirmed: 0.0 still let through 1200/1200 Case B false positives). Low non-zero
# values (0.10-0.25) already fully zeroed Case B but still let ~7% of Case A bursts
# through; 0.35 was the smallest value that zeroed BOTH Case A and Case B across every
# seed tested, and recall was flat (not worse) all the way up to 0.75 -- so there was
# no recall reason to go any higher. 1.0 (every single window in the run must
# corroborate) measurably cost extra recall on both the normal and blunted groups for
# no additional false-positive benefit, so it was rejected as needlessly strict.
# See the chat discussion / sweep output that shipped this change for the full numbers
# at each setting.
HR_CORROBORATION_THRESH_BPM = 1.0
HR_CORROBORATION_FRAC = 0.35

# --- HRV as a SECOND, INDEPENDENT corroboration path (added 2026-10, per explicit
# spec request to use HRV reduction, not just HR, as supporting evidence) ---
# Inspected hrv_eda_windowing.py before touching this (per that request): HRV windows
# carry their own low_confidence flag (beat_count < HRV_MIN_BEATS_DEFAULT=30 valid IBIs,
# or sample coverage too low -- see that file's compute_hrv_windows), and on real watch
# data this fires A LOT -- 59% of this real session's confirmed-sleep windows are
# hrv_low_confidence, and only 43% even have a non-null hrv_rmssd at all (Samsung's IBI
# batching behavior, documented earlier in this project -- not a wear-quality issue).
# That is exactly the case design requirement #4 warns about: HRV must never be treated
# as "missing -> assume stress," and a hard AND against HR (as the spec's own draft
# pseudocode literally reads) would make genuinely-psychological real windows fail
# corroboration constantly, since they would need BOTH signals valid+positive at once on
# a channel that's invalid more often than not. So HRV is wired in as an ALTERNATE,
# independent OR-path alongside HR -- corroboration passes if EITHER signal clears its
# own bar for its own run-fraction -- never a required AND, and a low-confidence/missing
# HRV window simply can't contribute to this path (it's excluded from the numerator, not
# treated as a free pass).
# Checked the actual RMSSD-deviation-from-baseline distributions (same per-session
# baseline window as HR/EDA, using only non-low-confidence baseline windows) before
# picking a bar: genuinely-psychological synthetic windows average -29.7ms (non-blunted)
# / -30.2ms (HR-blunted Case C) below baseline, with 94.5%/97.5% below -15ms -- a real,
# literature-consistent drop (sympathetic activation suppresses the vagally-mediated
# beat-to-beat variability RMSSD measures). The sustained_eda_drift negative control
# (Case B) only drops below -15ms in 0.2% of windows, and on the REAL sleep data RMSSD
# essentially never drops at all: of the 179 real sleep windows with valid-confidence
# HRV, 99.4% show RMSSD ABOVE baseline, not below -- consistent with the opposite,
# well-established physiology (parasympathetic/vagal dominance during sleep raises HRV,
# it doesn't lower it). So a meaningful RMSSD drop is a safe, physiologically-grounded,
# and in this generator's own Case-C data actually MORE sensitive signal than HR for a
# cardiac-blunted responder (consistent with "blunted" here meaning a weak mean-HR/
# chronotropic response, not necessarily blunted vagal withdrawal -- a real, documented
# distinction, not just a convenient generator choice).
# -15ms and the SAME run-fraction bar as HR (HR_CORROBORATION_FRAC) were chosen directly
# from that separation -- no reason found to give HRV its own, different fraction given
# how clean the separation already is at this threshold.
HRV_CORROBORATION_RMSSD_DROP_MS = 15.0

PSYCH_HR_BLUNTED_PROB = 0.25   # fraction of psychological (and mixed) sessions generated
                                # with a weak cardiac response -- HR barely rises even
                                # though EDA clearly does and the stressor is genuinely
                                # happening. Real, documented individual variability in
                                # sympathetic co-activation (not everyone's HR moves much
                                # under psychological stress). Exists so the report can
                                # show, honestly, how much recall the HR-corroboration
                                # fix above costs on a real responder like this, not just
                                # how well it fixes the sleep false-positive case.
PSYCH_HR_BLUNTED_FACTOR_RANGE = (0.05, 0.35)  # blunted sessions still get SOME HR
                                                # movement (real non-responders aren't
                                                # usually perfectly flat), just scaled
                                                # down to this fraction of the normal
                                                # -180*intensity response

SUSTAINED_EDA_DRIFT_SEC_RANGE = (600, 1200)       # 10-20 min per drift period -- modeled
                                                    # on Nami's real overnight EDA
                                                    # elevation (which in fact lasted the
                                                    # entire ~7.25h sleep period; 10-20
                                                    # min is a deliberately conservative,
                                                    # shorter and harder-to-catch
                                                    # stand-in, not a claim about how
                                                    # long real drift usually lasts)
SUSTAINED_EDA_DRIFT_RISE_RANGE_US = (1.0, 10.0)   # magnitude is in the same ballpark as
                                                    # Nami's real session (EDA sat
                                                    # roughly +3 to +16uS above its own
                                                    # baseline through sleep, per the
                                                    # real merged-window table)


def _cortisol_bump(minutes_since_onset, peak_minutes, peak_rise):
    """Log-normal-shaped rise-then-decay bump, same simplification as
    generate_synthetic_cortisol_dataset.py -- illustrative, not a validated HPA model."""
    t = max(minutes_since_onset, 1e-6)
    sigma = 0.5
    mu = np.log(peak_minutes)
    return peak_rise * np.exp(-((np.log(t) - mu) ** 2) / (2 * sigma ** 2))


class _SessionBuilder:
    """Builds one session's raw accel/hr/eda/skin-temp rows phase by phase, and keeps
    a parallel list of (start_ms, end_ms, phase_label) ground-truth intervals."""

    def __init__(self, session_id, t0, rng, baseline_hr_ibi, baseline_eda, baseline_skin):
        self.session_id = session_id
        self.rng = rng
        self.baseline_hr_ibi = baseline_hr_ibi
        self.baseline_eda = baseline_eda
        self.baseline_skin = baseline_skin
        self.accel_rows, self.hr_rows, self.eda_rows, self.skin_rows = [], [], [], []
        self.intervals = []  # (start_ms, end_ms, phase_label)
        self.t_accel = t0
        self.t_sec = t0
        self.eda_level = baseline_eda
        self.stressor_events = []  # (onset_ms, intensity, kind) for cortisol curve
        self.spontaneous_eda_intervals = []  # (start_ms, end_ms) -- Case A: BRIEF (45-90s)
                                              # EDA-only bursts, no motion/HR change,
                                              # ground truth stays "rest"
        self.sustained_eda_intervals = []  # (start_ms, end_ms) -- Case B: SUSTAINED
                                            # (10-20min) EDA-only elevation, no motion/HR
                                            # change, ground truth stays "rest". Tracked
                                            # separately from the brief bursts above
                                            # because this is the case that actually
                                            # clears MIN_PERSIST_WINDOWS and exposed the
                                            # real-data false-positive bug -- the report
                                            # needs to show Case A and Case B results
                                            # separately, not pooled.
        self.hr_blunted_flags = {}  # kind ("physical"/"psychological") -> bool, mirrors
                                     # the cortisol non-responder flags but for a weak HR
                                     # response specifically (see PSYCH_HR_BLUNTED_PROB)

    def add_phase(self, duration_sec, label, mean_ibi_ms, ibi_jitter_ms,
                  eda_target, eda_noise, skin_target, axis_noise_g,
                  is_spontaneous_eda=False, is_sustained_drift=False):
        phase_start = self.t_sec
        for _ in range(duration_sec):
            self._add_accel_second(axis_noise_g)
            self._add_hr_eda_skin_second(mean_ibi_ms, ibi_jitter_ms, eda_target, eda_noise, skin_target)
        self.intervals.append((phase_start, self.t_sec, label))
        if is_sustained_drift:
            self.sustained_eda_intervals.append((phase_start, self.t_sec))
        elif is_spontaneous_eda:
            self.spontaneous_eda_intervals.append((phase_start, self.t_sec))

    def _add_accel_second(self, axis_noise_g):
        n = int(ACCEL_RATE_HZ)
        for _ in range(n):
            noise = self.rng.normal(0, axis_noise_g, size=3)
            x, y, z = noise[0], noise[1], 1.0 + noise[2]
            self.accel_rows.append({
                "session_id": self.session_id, "participant_email": PARTICIPANT_EMAIL,
                "timestamp_ms": self.t_accel, "x_raw": round(x * ACCEL_COUNTS_PER_G),
                "y_raw": round(y * ACCEL_COUNTS_PER_G), "z_raw": round(z * ACCEL_COUNTS_PER_G),
                "status": -1, "received_at_ms": self.t_accel,
            })
            self.t_accel += round(1000 / ACCEL_RATE_HZ)

    def _add_hr_eda_skin_second(self, mean_ibi_ms, ibi_jitter_ms, eda_target, eda_noise, skin_target):
        t = self.t_sec
        rng = self.rng
        n_ibi = int(rng.integers(1, 4))
        ibis = [int(max(300, round(rng.normal(mean_ibi_ms, ibi_jitter_ms)))) for _ in range(n_ibi)]
        statuses = [0] * n_ibi
        hr_bpm = round(60000.0 / np.mean(ibis))
        self.hr_rows.append({
            "session_id": self.session_id, "participant_email": PARTICIPANT_EMAIL,
            "timestamp_ms": t, "heart_rate_bpm": hr_bpm, "hr_status": 0,
            "ibi_list_ms": json.dumps(ibis), "ibi_status_list": json.dumps(statuses),
            "received_at_ms": t,
        })
        self.eda_level += (eda_target - self.eda_level) * 0.3
        conductance = max(0.0, self.eda_level + rng.normal(0, eda_noise))
        self.eda_rows.append({
            "session_id": self.session_id, "participant_email": PARTICIPANT_EMAIL,
            "timestamp_ms": t, "conductance_uS": round(conductance, 4),
            "status": 0, "received_at_ms": t,
        })
        if t % 15000 < 1000:
            self.skin_rows.append({
                "session_id": self.session_id, "participant_email": PARTICIPANT_EMAIL,
                "timestamp_ms": t, "ambient_c": round(rng.normal(24.0, 0.3), 3),
                "skin_c": round(skin_target + rng.normal(0, 0.05), 3),
                "status": 0, "received_at_ms": t,
            })
        self.t_sec += 1000


def _build_session(session_type, session_id, t0, rng):
    baseline_hr_ibi = rng.normal(850, 40)
    baseline_eda = max(0.3, rng.normal(1.8, 0.5))
    baseline_skin = 33.5

    b = _SessionBuilder(session_id, t0, rng, baseline_hr_ibi, baseline_eda, baseline_skin)
    b.add_phase(BASELINE_SEC, "rest", baseline_hr_ibi, 35, baseline_eda, 0.03, baseline_skin, 0.01)

    if session_type == "rest":
        b.add_phase(420, "rest", baseline_hr_ibi, 35, baseline_eda, 0.03, baseline_skin, 0.01)

    elif session_type == "physical":
        intensity = float(np.clip(rng.beta(2, 2), 0.05, 0.98))
        dur = int(rng.integers(*PHYS_SEC_RANGE))
        onset_ms = b.t_sec
        b.add_phase(dur, "physical",
                    baseline_hr_ibi - 220 * intensity, 10,
                    baseline_eda + 2.0 * intensity, 0.05,
                    baseline_skin + 0.6 * intensity,
                    0.05 + 0.35 * intensity)
        b.stressor_events.append((onset_ms, intensity, "physical"))
        b.add_phase(RECOVERY_SEC, "recovery", baseline_hr_ibi, 35,
                    baseline_eda + 0.2 * intensity, 0.03, baseline_skin + 0.1 * intensity, 0.01)

    elif session_type == "psychological":
        intensity = float(np.clip(rng.beta(2, 2), 0.05, 0.98))
        dur = int(rng.integers(*PSYCH_SEC_RANGE))
        onset_ms = b.t_sec
        # Case C: a fraction of real psychological-stress sessions get a weak cardiac
        # response (HR barely rises even though EDA clearly does) -- see
        # PSYCH_HR_BLUNTED_PROB's comment. Tracked in hr_blunted_flags so the report can
        # show how much this costs the HR-corroboration fix's recall honestly.
        is_hr_blunted = bool(rng.random() < PSYCH_HR_BLUNTED_PROB)
        hr_factor = float(rng.uniform(*PSYCH_HR_BLUNTED_FACTOR_RANGE)) if is_hr_blunted else 1.0
        b.hr_blunted_flags["psychological"] = is_hr_blunted
        b.add_phase(dur, "psychological",
                    baseline_hr_ibi - 180 * intensity * hr_factor, 12,
                    baseline_eda + 3.2 * intensity, 0.05,
                    baseline_skin - 0.5 * intensity,
                    0.01)
        b.stressor_events.append((onset_ms, intensity, "psychological"))
        b.add_phase(RECOVERY_SEC, "recovery", baseline_hr_ibi, 35,
                    baseline_eda + 0.3 * intensity, 0.03, baseline_skin - 0.1 * intensity, 0.01)

    elif session_type == "mixed":
        intensity_phys = float(np.clip(rng.beta(2, 2), 0.05, 0.98))
        intensity_psych = float(np.clip(rng.beta(2, 2), 0.05, 0.98))
        dur_phys = int(rng.integers(*PHYS_SEC_RANGE))
        dur_psych = int(rng.integers(*PSYCH_SEC_RANGE))

        onset_phys = b.t_sec
        b.add_phase(dur_phys, "physical",
                    baseline_hr_ibi - 220 * intensity_phys, 10,
                    baseline_eda + 2.0 * intensity_phys, 0.05,
                    baseline_skin + 0.6 * intensity_phys,
                    0.05 + 0.35 * intensity_phys)
        b.stressor_events.append((onset_phys, intensity_phys, "physical"))

        b.add_phase(GAP_SEC, "rest", baseline_hr_ibi, 35, baseline_eda, 0.03, baseline_skin, 0.01)

        onset_psych = b.t_sec
        is_hr_blunted = bool(rng.random() < PSYCH_HR_BLUNTED_PROB)
        hr_factor = float(rng.uniform(*PSYCH_HR_BLUNTED_FACTOR_RANGE)) if is_hr_blunted else 1.0
        b.hr_blunted_flags["psychological"] = is_hr_blunted
        b.add_phase(dur_psych, "psychological",
                    baseline_hr_ibi - 180 * intensity_psych * hr_factor, 12,
                    baseline_eda + 3.2 * intensity_psych, 0.05,
                    baseline_skin - 0.5 * intensity_psych,
                    0.01)
        b.stressor_events.append((onset_psych, intensity_psych, "psychological"))

        b.add_phase(RECOVERY_SEC, "recovery", baseline_hr_ibi, 35,
                    baseline_eda + 0.1 * intensity_psych, 0.03, baseline_skin - 0.05 * intensity_psych, 0.01)

    elif session_type == "daily_baseline":
        # No stressor at all -- this type exists for the "I saw an EDA spike even
        # though I was asleep" case. Several quiet periods (small randomized offsets,
        # loosely standing in for different times of day -- NOT a real circadian
        # model) with brief, spontaneous EDA-only bursts scattered through: no motion,
        # no HR change, nothing "happened" except conductance briefly rising on its
        # own. Real spontaneous skin conductance responses like this are well
        # documented during quiet rest and sleep-stage transitions -- they are NOT
        # evidence of psychological stress. Ground truth for every window here stays
        # "rest" (no stressor event), but spontaneous_eda_intervals is tracked
        # separately so the report can show how often the classifier mistakes one of
        # these for "psychological" anyway (it will, because the current rule is just
        # "EDA up + not moving" -- that's the point of generating this case).
        n_quiet_periods = 4
        for _ in range(n_quiet_periods):
            period_drift = rng.normal(0, 0.15)
            b.add_phase(240, "rest", baseline_hr_ibi + rng.normal(0, 15), 35,
                        max(0.2, baseline_eda + period_drift), 0.03,
                        baseline_skin + rng.normal(0, 0.1), 0.01)
            if rng.random() < 0.7:
                burst_size = max(0.8, rng.normal(2.2, 0.6))
                burst_dur = int(rng.integers(45, 90))
                b.add_phase(burst_dur, "rest", baseline_hr_ibi, 35,
                            baseline_eda + burst_size, 0.06, baseline_skin, 0.01,
                            is_spontaneous_eda=True)

    elif session_type == "sustained_eda_drift":
        # Case B (CRITICAL -- this is the real-data bug, see HR_CORROBORATION_FRAC's
        # comment). EDA drifts up and STAYS up for many minutes, no motion, no HR
        # change -- the synthetic stand-in for Nami's real Oct 6 overnight recording,
        # where EDA sat several uS above baseline for the entire ~7.25h of confirmed
        # sleep while HR never once crossed above baseline. Ground truth is "rest"
        # throughout (nothing is happening). Unlike the brief daily_baseline bursts
        # above (Case A, 45-90s -- already caught by the existing persistence filter,
        # since they rarely clear MIN_PERSIST_WINDOWS), these sustained drifts easily
        # clear it -- that's exactly what the OR-logic rule got wrong on real data.
        # HR uses the session's plain baseline_hr_ibi throughout both sub-phases below
        # -- deliberately NOT given its own independent per-phase random offset (unlike
        # daily_baseline's quiet periods above), because the whole point of this case is
        # "EDA moves, HR genuinely does not": an independent rng.normal() offset on each
        # phase would add a spurious HR shift between the quiet and drift sub-phases
        # that has nothing to do with real physiology, and would quietly make this test
        # easier or harder to catch depending on which way the draw happened to go. The
        # only HR variation here is the same per-sample ibi_jitter_ms=35 every session
        # type gets.
        n_drift_periods = 2
        for _ in range(n_drift_periods):
            b.add_phase(180, "rest", baseline_hr_ibi, 35,
                        baseline_eda, 0.03, baseline_skin + rng.normal(0, 0.1), 0.01)
            drift_dur = int(rng.integers(*SUSTAINED_EDA_DRIFT_SEC_RANGE))
            drift_rise = float(rng.uniform(*SUSTAINED_EDA_DRIFT_RISE_RANGE_US))
            b.add_phase(drift_dur, "rest", baseline_hr_ibi, 35,
                        baseline_eda + drift_rise, 0.04, baseline_skin + rng.normal(0, 0.1), 0.01,
                        is_sustained_drift=True)
    else:
        raise ValueError(session_type)

    return b


def _make_cortisol_for_session(session_id, builder, rng):
    participant_baseline = max(2.0, rng.normal(5.0, 0.8))
    session_start = builder.intervals[0][0]
    session_end = builder.intervals[-1][1]

    peak_minutes_by_event = []
    blunted_by_kind = {}  # kind -> True/False, for reporting which phases in this
                           # session had a blunted/non-responder cortisol curve
    for onset_ms, intensity, kind in builder.stressor_events:
        k = K_PHYS_CORTISOL if kind == "physical" else K_PSYCH_CORTISOL
        non_responder_prob = NON_RESPONDER_PROB_PHYS if kind == "physical" else NON_RESPONDER_PROB_PSYCH
        is_non_responder = bool(rng.random() < non_responder_prob)
        if is_non_responder:
            # blunted response: the stressor happened (HR/EDA/motion still show it),
            # but cortisol stays close to baseline -- intensity doesn't matter here,
            # that's the point of a non-responder.
            peak_rise = max(0.0, rng.normal(BLUNTED_PEAK_RISE_MEAN, BLUNTED_PEAK_RISE_STD))
        else:
            peak_rise = max(0.0, k * intensity + rng.normal(0, 0.3))
        peak_minutes = rng.uniform(*PEAK_MINUTES_RANGE)
        peak_minutes_by_event.append((onset_ms, peak_rise, peak_minutes, kind))
        blunted_by_kind[kind] = is_non_responder

    rows = []
    for off in SAMPLE_OFFSETS_MIN:
        sample_ms = session_start + off * 60_000
        val = participant_baseline
        for onset_ms, peak_rise, peak_minutes, kind in peak_minutes_by_event:
            mins_since = (sample_ms - onset_ms) / 60_000.0
            if mins_since > 0:
                val += _cortisol_bump(mins_since, peak_minutes, peak_rise)
        val += rng.normal(0, 0.15)
        rows.append({
            "session_id": session_id, "participant_email": PARTICIPANT_EMAIL,
            "sample_time_ms": sample_ms, "cortisol_value": round(max(0.1, val), 3),
        })
    return pd.DataFrame(rows), participant_baseline, peak_minutes_by_event, blunted_by_kind


def _label_ground_truth(merged, intervals_by_session):
    """Collapses each session's phase intervals (rest/physical/psychological/recovery)
    onto the merged windows by midpoint-in-interval lookup, and maps the fine-grained
    labels down to the 3 buckets the classifier predicts (rest/physical/psychological;
    'recovery' counts as 'rest' for scoring -- no stressor is acting on it anymore)."""
    collapse = {"rest": "rest", "recovery": "rest", "physical": "physical", "psychological": "psychological"}
    out = []
    for _, row in merged.iterrows():
        mid = (row["window_start_ms"] + row["window_end_ms"]) / 2.0
        label = "rest"
        for start_ms, end_ms, phase in intervals_by_session.get(row["session_id"], []):
            if start_ms <= mid < end_ms:
                label = phase
                break
        out.append(collapse.get(label, "rest"))
    return out


def _label_interval_hits(merged, intervals_by_session_map):
    """True for a window whose midpoint falls inside one of a session's tracked
    EDA-only-elevation intervals (brief Case A bursts, or sustained Case B drift --
    see 'daily_baseline' / 'sustained_eda_drift' in _build_session). These are always
    ground-truth 'rest' (no stressor), but EDA is elevated anyway -- this column is
    what lets the report say how often the classifier calls one of these
    'psychological' (or, with the corroboration fix, 'non_stress_arousal') by
    mistake. Shared helper for both the Case A and Case B interval maps, so the two
    get identical lookup logic and can't drift apart."""
    out = []
    for _, row in merged.iterrows():
        mid = (row["window_start_ms"] + row["window_end_ms"]) / 2.0
        hit = any(start_ms <= mid < end_ms
                  for start_ms, end_ms in intervals_by_session_map.get(row["session_id"], []))
        out.append(hit)
    return out


def _classify(merged):
    """Rule-based, multimodal, motion-gated classifier -- see module docstring for the
    logic.
    Stage 1a (elevated): HR or EDA above this session's own baseline (first
    BASELINE_SEC seconds) -- same as before, this still just decides "is anything
    going on at all," it's not what decides which label.
    Stage 1b (persistence): elevated must hold for >=MIN_PERSIST_WINDOWS consecutive
    windows to count -- a single elevated window alone is treated as noise/rest. This
    is what keeps a one-window spontaneous EDA blip (Case A) from counting at all.
    Stage 2 (motion gate): a persistent-elevated, MOVING window predicts "physical" --
    unchanged.
    Stage 3 (multimodal corroboration -- added 2026-10, see HR_CORROBORATION_FRAC's and
    HRV_CORROBORATION_RMSSD_DROP_MS's comments): a persistent-elevated, NON-moving
    window used to predict "psychological" automatically just because EDA (and/or HR)
    cleared the Stage-1a bar. That is exactly the real-data bug: a sustained EDA-only
    elevation during Nami's confirmed sleep cleared the persistence bar on EDA alone
    and got called "psychological" on ~90% of that session's sleep windows, with zero
    HR or HRV corroboration. Now, the RUN of persistent-elevated non-moving windows
    (not each window individually, to avoid flickering mid-episode) is only called
    "psychological" if EITHER HR was elevated OR HRV (RMSSD) showed a stress-compatible
    reduction, each in at least their own run-fraction of that run's windows. Neither
    is a mandatory AND, and each is independently quality-gated (HRV never corroborates
    off a low-confidence/missing window) -- see each constant's own comment for the
    reasoning. Otherwise the run predicts "non_stress_arousal": EDA genuinely moved and
    isn't hidden behind a confident "rest" label, but it is not reported as stress
    either."""
    preds = []
    for session_id, sess in merged.groupby("session_id"):
        sess = sess.sort_values("window_start_ms").copy()
        baseline_mask = sess["window_start_ms"] < (sess["window_start_ms"].min() + BASELINE_SEC * 1000)
        base_hr = sess.loc[baseline_mask, "hrv_mean_hr_bpm"].mean()
        base_eda = sess.loc[baseline_mask, "eda_mean"].mean()
        # RMSSD baseline uses only non-low-confidence baseline windows -- a
        # low-confidence baseline reading (too few valid IBI beats) shouldn't anchor
        # what "normal" RMSSD means for the rest of the session. nan if the baseline
        # itself has no valid-confidence HRV at all; rmssd_dev below is then all-NaN
        # for this session and hrv_corrob_raw is all False -- degrades gracefully,
        # never blocks a decision, see HRV_CORROBORATION_RMSSD_DROP_MS's comment.
        hrv_low_conf = sess["hrv_low_confidence"].fillna(True).astype(bool)
        base_rmssd = sess.loc[baseline_mask & ~hrv_low_conf, "hrv_rmssd"].mean()
        hr_thresh = HR_ELEVATED_THRESH_BPM
        eda_thresh = EDA_ELEVATED_THRESH_US

        hr_dev = (sess["hrv_mean_hr_bpm"] - base_hr).fillna(0.0)
        eda_dev = (sess["eda_mean"] - base_eda).fillna(0.0)
        rmssd_dev = sess["hrv_rmssd"] - base_rmssd  # NaN where either side is NaN
        is_moving = sess["accel_any_motion_flagged"].fillna(False).astype(bool)

        hr_elevated_raw = hr_dev > hr_thresh
        eda_elevated_raw = eda_dev > eda_thresh
        elevated_raw = hr_elevated_raw | eda_elevated_raw

        # Run-length of consecutive identical elevated_raw values, in window order --
        # a window only "counts" if it's part of a run of True values at least
        # MIN_PERSIST_WINDOWS long.
        run_id = (elevated_raw != elevated_raw.shift()).cumsum()
        run_len = elevated_raw.groupby(run_id).transform("size")
        persistent_elevated = elevated_raw & (run_len >= MIN_PERSIST_WINDOWS)

        # HR corroboration uses its OWN, much lower bar (HR_CORROBORATION_THRESH_BPM,
        # not HR_ELEVATED_THRESH_BPM) -- see that constant's comment for why reusing
        # the "is anything elevated" bar collapsed real recall. Run-level (not
        # per-window) fraction, via transform("mean") grouped by the same run_id, so a
        # brief dip in EDA mid-episode doesn't reset the corroboration count, and a
        # single HR blip mid-run doesn't flip just that one window.
        hr_corrob_raw = hr_dev > HR_CORROBORATION_THRESH_BPM
        hr_frac_in_run = hr_corrob_raw.groupby(run_id).transform("mean")
        hr_corroborated = hr_frac_in_run >= HR_CORROBORATION_FRAC

        # HRV corroboration -- an INDEPENDENT second path, not required alongside HR
        # (see HRV_CORROBORATION_RMSSD_DROP_MS's comment). A NaN rmssd_dev (missing or
        # low-confidence HRV in this window) compares False against the threshold, so
        # it simply can't contribute to the numerator -- never a free pass toward
        # "psychological," exactly what design requirement #4 asked for.
        hrv_corrob_raw = rmssd_dev < -HRV_CORROBORATION_RMSSD_DROP_MS
        hrv_frac_in_run = hrv_corrob_raw.fillna(False).groupby(run_id).transform("mean")
        hrv_corroborated = hrv_frac_in_run >= HR_CORROBORATION_FRAC

        corroborated = hr_corroborated | hrv_corroborated

        for idx, pe, mv, hd, ed, corrob in zip(
                sess.index, persistent_elevated, is_moving, hr_dev, eda_dev, corroborated):
            if not pe:
                pred = "rest"
            elif mv:
                pred = "physical"
            elif corrob:
                pred = "psychological"
            else:
                pred = "non_stress_arousal"
            preds.append((idx, pred, hd, ed, bool(mv), bool(pe)))

    pred_df = pd.DataFrame(preds, columns=["_idx", "predicted_phase", "hr_dev_from_baseline",
                                            "eda_dev_from_baseline", "is_moving",
                                            "persistent_elevation"]).set_index("_idx")
    return pred_df.reindex(merged.index)


def generate(n_per_type, seed, out_dir):
    # Two INDEPENDENT random streams, not one shared rng -- cortisol sampling (how
    # many saliva draws, their noise) used to share a single rng with the
    # physiology/classifier generation (HR/EDA/accel/motion). That meant a
    # cortisol-only change (e.g. dropping from 8 to 5 samples per session) shifted
    # every later session's physiology too, by consuming a different number of
    # rng.normal() calls before the next session's turn -- changing the classifier's
    # accuracy number for a reason that had nothing to do with the classifier. Found
    # when 5-sample cortisol (requested for a real saliva-assay budget constraint)
    # silently moved overall accuracy from 96.7% to 95.3%. Splitting the seed with
    # SeedSequence.spawn() gives cortisol its own stream so changing cortisol-related
    # constants (sample count, peak timing, non-responder rates, k values) can never
    # again perturb the HR/EDA/motion data or the classifier's results.
    seed_seq = np.random.SeedSequence(seed)
    physio_seed, cortisol_seed = seed_seq.spawn(2)
    rng = np.random.default_rng(physio_seed)
    cortisol_rng = np.random.default_rng(cortisol_seed)
    os.makedirs(out_dir, exist_ok=True)

    session_types = ["rest", "physical", "psychological", "mixed", "daily_baseline", "sustained_eda_drift"]
    all_accel, all_hr, all_eda, all_skin, all_cortisol = [], [], [], [], []
    intervals_by_session = {}
    blunted_by_session = {}  # session_id -> {"physical": bool, "psychological": bool}
    spontaneous_by_session = {}  # session_id -> [(start_ms, end_ms), ...] -- Case A (brief)
    sustained_by_session = {}  # session_id -> [(start_ms, end_ms), ...] -- Case B (sustained)
    hr_blunted_by_session = {}  # session_id -> {"psychological": bool} -- Case C
    t0 = 1_765_000_000_000
    session_counter = 0

    for stype in session_types:
        for i in range(n_per_type):
            session_counter += 1
            session_id = f"synthv2_{stype}_{i+1:03d}"
            session_t0 = t0 + session_counter * 3 * 60 * 60 * 1000
            builder = _build_session(stype, session_id, session_t0, rng)
            cortisol_df, base, _peaks, blunted_by_kind = _make_cortisol_for_session(
                session_id, builder, cortisol_rng)

            all_accel.append(pd.DataFrame(builder.accel_rows))
            all_hr.append(pd.DataFrame(builder.hr_rows))
            all_eda.append(pd.DataFrame(builder.eda_rows))
            all_skin.append(pd.DataFrame(builder.skin_rows))
            all_cortisol.append(cortisol_df)
            intervals_by_session[session_id] = builder.intervals
            blunted_by_session[session_id] = blunted_by_kind
            spontaneous_by_session[session_id] = builder.spontaneous_eda_intervals
            sustained_by_session[session_id] = builder.sustained_eda_intervals
            hr_blunted_by_session[session_id] = dict(builder.hr_blunted_flags)

    accel_raw = pd.concat(all_accel, ignore_index=True)
    hr_raw = pd.concat(all_hr, ignore_index=True)
    eda_raw = pd.concat(all_eda, ignore_index=True)
    skin_raw = pd.concat(all_skin, ignore_index=True)
    cortisol_df = pd.concat(all_cortisol, ignore_index=True)

    accel_windows = accel_windowing.compute_windows(accel_raw, window_sec=5.0)
    hrv_windows = hrv_eda_windowing.compute_hrv_windows(hr_raw, window_sec=30.0)
    eda_windows = hrv_eda_windowing.compute_eda_windows(eda_raw, window_sec=30.0)
    skin_windows = skin_temp_windowing.compute_windows(skin_raw, window_sec=30.0)

    merged = merge_windows.merge_all(
        hrv_windows, eda_windows, accel_windows, skin_windows,
        cortisol=cortisol_df, cortisol_lag_min=merge_windows.CORTISOL_LAG_MIN_DEFAULT)
    merged = merged.reset_index(drop=True)

    merged["ground_truth_phase"] = _label_ground_truth(merged, intervals_by_session)
    merged["session_type"] = merged["session_id"].str.extract(r"synthv2_(\w+)_\d+")[0]

    # True = that session's physical/psychological phase was a cortisol "non-responder"
    # (HR/EDA/motion still show the stressor, cortisol barely moved). NaN = that
    # session doesn't have a phase of that kind at all (e.g. a "rest" session has
    # neither).
    merged["physical_cortisol_blunted"] = merged["session_id"].map(
        lambda sid: blunted_by_session.get(sid, {}).get("physical", np.nan))
    merged["psychological_cortisol_blunted"] = merged["session_id"].map(
        lambda sid: blunted_by_session.get(sid, {}).get("psychological", np.nan))

    merged["is_spontaneous_eda_burst"] = _label_interval_hits(merged, spontaneous_by_session)
    merged["is_sustained_eda_drift"] = _label_interval_hits(merged, sustained_by_session)
    merged["psychological_hr_blunted"] = merged["session_id"].map(
        lambda sid: hr_blunted_by_session.get(sid, {}).get("psychological", np.nan))

    pred_df = _classify(merged)
    merged = pd.concat([merged, pred_df], axis=1)

    accel_raw.to_csv(os.path.join(out_dir, "v2_accel_raw.csv"), index=False)
    cortisol_df.to_csv(os.path.join(out_dir, "v2_cortisol_samples.csv"), index=False)
    merged.to_csv(os.path.join(out_dir, "v2_full_training_set.csv"), index=False)

    return merged


def _print_report(merged):
    overall_acc = (merged["predicted_phase"] == merged["ground_truth_phase"]).mean()
    print(f"\nOverall classifier accuracy: {overall_acc:.1%}  (n={len(merged)} windows)\n")

    print("Confusion matrix (rows = ground truth, columns = predicted):")
    confusion = pd.crosstab(merged["ground_truth_phase"], merged["predicted_phase"])
    print(confusion.to_string())

    print("\nPer-session-type accuracy:")
    per_type = merged.groupby("session_type").apply(
        lambda g: (g["predicted_phase"] == g["ground_truth_phase"]).mean())
    print(per_type.to_string())

    print("\nCortisol max value per session type (sanity check -- does the synthetic "
          "cortisol rise for physical and psychological alike, and NOT for rest):")
    cort_by_type = merged.groupby("session_type")["cortisol_value"].agg(["min", "mean", "max"])
    print(cort_by_type.to_string())

    print("\nCortisol 'non-responder' sessions (stressor clearly happened per HR/EDA/motion, "
          "but cortisol barely moved -- this is the 'what if it doesn't spike much' case):")
    for kind, col in [("physical", "physical_cortisol_blunted"), ("psychological", "psychological_cortisol_blunted")]:
        sess_flags = merged.drop_duplicates("session_id")[["session_id", col]].dropna(subset=[col])
        n_blunted = int(sess_flags[col].sum())
        n_total = len(sess_flags)
        print(f"  {kind}: {n_blunted} / {n_total} sessions were non-responders")

    print("\n" + "=" * 72)
    print("FALSE 'PSYCHOLOGICAL STRESS' RATE ON NEGATIVE CONTROLS")
    print("(both cases below are ground-truth 'rest' -- nothing is actually happening --")
    print(" but EDA moves anyway. Case B is the one the real Oct 6 sleep recording found.)")
    print("=" * 72)

    burst_windows = merged[merged["is_spontaneous_eda_burst"]]
    if len(burst_windows):
        n_mislabeled_psych = int((burst_windows["predicted_phase"] == "psychological").sum())
        n_non_stress = int((burst_windows["predicted_phase"] == "non_stress_arousal").sum())
        print(f"\nCase A -- brief (45-90s) spontaneous no-motion EDA bursts (daily_baseline "
              f"sessions; the original 'EDA spiked even though I was asleep' case):")
        print(f"  {len(burst_windows)} windows contain a brief burst; classifier called "
              f"{n_mislabeled_psych} of them 'psychological' (false stress call), "
              f"{n_non_stress} 'non_stress_arousal'")
        print(f"  predicted-label breakdown on these windows:")
        print(f"  {burst_windows['predicted_phase'].value_counts().to_string()}")

    drift_windows = merged[merged["is_sustained_eda_drift"]]
    if len(drift_windows):
        n_mislabeled_psych = int((drift_windows["predicted_phase"] == "psychological").sum())
        n_non_stress = int((drift_windows["predicted_phase"] == "non_stress_arousal").sum())
        print(f"\nCase B (CRITICAL) -- sustained (10-20min) no-motion EDA-only elevation "
              f"(sustained_eda_drift sessions; synthetic stand-in for the real Oct 6 "
              f"overnight recording, where this exact pattern held for the entire ~7.25h "
              f"of confirmed sleep):")
        print(f"  {len(drift_windows)} windows fall inside a sustained drift; classifier called "
              f"{n_mislabeled_psych} of them 'psychological' (false stress call), "
              f"{n_non_stress} 'non_stress_arousal'")
        print(f"  predicted-label breakdown on these windows:")
        print(f"  {drift_windows['predicted_phase'].value_counts().to_string()}")

    print("\n" + "=" * 72)
    print("CASE C -- sensitivity trade-off: real psychological stress, weak HR response")
    print("(what the HR-corroboration fix above might cost -- honesty check, not a win)")
    print("=" * 72)
    psych_gt = merged[merged["ground_truth_phase"] == "psychological"]
    for label, mask in [("normal HR response", psych_gt["psychological_hr_blunted"] == False),
                         ("HR-blunted (Case C)", psych_gt["psychological_hr_blunted"] == True)]:
        g = psych_gt[mask]
        if len(g) == 0:
            continue
        recall = (g["predicted_phase"] == "psychological").mean()
        n_as_non_stress = int((g["predicted_phase"] == "non_stress_arousal").sum())
        n_as_rest = int((g["predicted_phase"] == "rest").sum())
        print(f"  {label}: n={len(g)} windows, recall (predicted 'psychological')={recall:.1%}, "
              f"of the misses: {n_as_non_stress} called 'non_stress_arousal', {n_as_rest} called 'rest'")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--n-per-type", type=int, default=5)
    ap.add_argument("--seed", type=int, default=RNG_SEED_DEFAULT)
    ap.add_argument("--out-dir", default="synth_v2_out")
    args = ap.parse_args()

    print(f"Generating {args.n_per_type} sessions each of rest/physical/psychological/mixed/"
          f"daily_baseline/sustained_eda_drift "
          f"(seed={args.seed})...\nReminder: synthetic, zero evidentiary value -- see module docstring.\n")
    merged = generate(args.n_per_type, args.seed, args.out_dir)
    print(f"Wrote {len(merged)} merged+labeled+classified windows -> "
          f"{args.out_dir}/v2_full_training_set.csv")
    _print_report(merged)


if __name__ == "__main__":
    main()
