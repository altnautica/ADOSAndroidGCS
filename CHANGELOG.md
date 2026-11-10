# Changelog

All notable changes to ADOS Android GCS are recorded here. Versions follow the
app's `versionName`.

## [Unreleased]

- Switching views no longer freezes the flight screen: a video drawn with a
  software canvas (the demo drone) renders into a texture view, whose teardown
  wakes the drawing thread instead of waiting on it.
- Pause is refused while the flight controller flies a failsafe return or
  landing, on every route: the pilot's own refusal, and on the routes straight
  to the flight controller when it reports a critical state or entered a return
  or landing no operator commanded here.
- Disarm waits until the drone is known to be on the ground, from the pilot or
  the flight controller's own height and vertical speed.
- The cockpit spec gains contextual tips, refusal, alert and tip texts and the
  shared layout values (confirm holds, launch range, Menu order, storage
  thresholds, split divider); hold-reason labels follow the pilot's wire strings.
- Spec text, refusal text, link-loss line, status pill and the tips queue are
  shared ports held to the same golden cases as the web cockpit.
- Fly Here sends a map point to the flight controller when the pilot's state
  is stale, instead of waiting on a pilot that cannot answer.

## [0.4.0] - 2026-11-10

### Flight screen

- A new landscape cockpit with one top bar: battery with reserve and time to
  empty, link, navigation source and controller on the left; the layer button,
  Menu and STOP on the right. STOP is always fully visible, never scrolled away
  or moved into the Menu. A status pill in the top bar carries warnings and
  relayed status texts.
- A clean centre: reticle, heading and horizon marks, the AR route and detection
  boxes. The flight instruments (pitch ladder, speed and altitude tapes, heading
  scale) draw only at the new Instruments layer level.
- A bottom telemetry row with distance, height, horizontal and vertical speed,
  and altitude above ground with its reference named.
- A mission chip with the time to the next waypoint and a transition chip for
  VTOL aircraft.

### Home and controller

- A console-style Home stage and a Quick menu, including the mission from the
  drone.
- DualShock 4 support: pairing guide, button maps, a live input test, light bar
  colours, and a short controller tour the first time a pad connects in the
  cockpit. In the cockpit, PS is the brake and return button.
- A controls editor with the controller drawing, calibration and binding rules.
  It lists extension bindings that collide with a key already in use.

### Data layers

- Route, obstacles, depth and detection targets drawn over the video in one
  layer canvas, with a layer button and a Vision side panel.
- Every layer shows its age: it dims after 500 ms without fresh data and hides
  after 2 s with an "unavailable" notice.
- When the live navigation stream cannot open, the cockpit polls at 2 Hz and
  says "Reduced update rate (radio)".

### Safety

- Pause, return, land and kill reach the flight controller's own command path
  when the onboard pilot channel is down or stale, instead of being refused.
- Pilot actions wait for the drone's answer and show the reason for a refusal.
  Changing the flight mode stops the active motion skill first.
- Sticks reach the flight controller directly only in manual control or for a
  flight controller attached to the phone.
- Going to a map point while in the air asks for a hold to confirm. Under ADOS
  control, when the drone offers it, the onboard pilot flies the point and takes
  over from any running mission or skill; otherwise the flight controller's
  reposition flies it. Each pilot refusal reads as a sentence.
- The newest flight command always wins: an older pause, return, land or kill
  still waiting on the pilot never falls back to the flight controller after a
  newer command (including a Mode sheet change), and if the pilot accepts the
  older one late, the newer command is sent again so the drone ends on it. Only
  flight commands count (mode changes, arm, disarm, takeoff, Fly Here and the
  recovery commands); an EKF source switch or other configuration command never
  cancels a pending pause, return, land or kill.
- Fly Here refused during a failsafe return or landing reads as a sentence.
- A hidden layer's "unavailable" chip sits in the video's free corner, clear of
  the top bar and banners.
- Relayed flight controller texts keep the newest critical alert and the newest
  warnings in the status pill.

### Setup

- An aircraft setup wizard with guided calibration, routed through the drone's
  agent when one is connected.
- The planner flags a different plan on the drone, draws it dashed and offers
  Load from drone.
