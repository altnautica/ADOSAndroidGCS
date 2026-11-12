# Changelog

All notable changes to ADOS Android GCS are recorded here. Versions follow the
app's `versionName`.

## [Unreleased]

### Added

- **Fly Here on the local map.** A long-press on the local minimap offers Fly
  Here without GNSS, converting the point through the flight controller's
  origin. A long-press the pilot would refuse now says why (failsafe, landed,
  control mode, not in GUIDED, pilot not answering) and opens no confirm.
- **Link popover.** Tapping the link cell opens RSSI and LQ on every width,
  and a reduced update rate is named on the cell, the popover and the Menu.
- **Video retry.** A failed stream shows a Retry button.
- **Menu rows.** Layers, Controls, Extension panels and Set up aircraft (only
  when setup is needed), in the shared spec's Menu order.
- **Pose age.** The navigation source popover shows how old the newest pose is.
- **File-size check.** `scripts/check-file-size.sh` holds Kotlin files to 500
  lines, with a baseline for the few larger ones, and runs in CI.

### Changed

- **Sentence-case flight states.** The flight-state chip reads `Link`,
  `Pilot · Normal` and `Follow · Orbit 8 m` as the shared cockpit spec words them,
  and a Fly Here long-press with no flight controller link says so.

- **Sharper, quieter flight screen.** Smaller radii, near-opaque panels with
  a light blur, hairline borders and one control height across the top bar.
  The flight-state chip and the status pill are outlines with a coloured dot,
  and their words are sentence case from the shared spec. The follow rail,
  lock chip, layer controls, vision panel, camera tabs, banners and skill chip
  draw on the same chrome.
- **Outline status chips.** Status chips, reach badges and chip actions are
  1 dp outlines with no fill; vehicle tiles are rounded squares.
- **Flight-screen words.** The remaining flight-screen strings come from the
  shared spec keys; unused resources are gone.
- **Per-vehicle layout.** The split ratio and view are remembered per vehicle.
- **Demo.** The demo flight controller's mode follows the pinned state, and
  the demo uses varied warning texts.

### Fixed

- **Demo Fly Here.** A LAND picked while a demo goto still shows is no longer
  switched back to GUIDED when the goto window ends.

- **Unknown arm state.** Without a flight-controller link the pilot's arm
  state is unknown and is treated as possibly airborne: no tips, Land rather
  than Take off, pre-flight items hidden.
- **Stale pilot.** A pilot frame is used only while it is fresh, with the
  spec's stale age (a frame at exactly the stale age is stale); otherwise the
  heartbeat answers.
- **Cancelled controller press.** A chord start or a pad disconnect cancels a
  held select: no button, chip, tile or menu row clicks on that release, and a
  hold-to-confirm or a slide-to-confirm driven by the key stops without firing.
- **Banner cause.** Pilot banners name their cause.
- **Phone layout.** Snackbars sit above the bottom row and the map card no
  longer repeats its title; the centre banner no longer covers the heading.
- **Hit targets.** Card dots, the Menu, the lock chip's release and each half
  of the follow rail's Strict/Smart toggle are 48 dp targets.
- **Credential vault test** runs on a test dispatcher and no longer flakes.
- **Outside the flight screen.** Home, Connect, node, configure, planner, logs
  and settings screens no longer show raw ids, enum names or server messages,
  use one "Add a node" label, and their small rows are 48 dp targets.
- **Manual control.** Stick input follows the controller's stick mode,
  calibration and dead zone.
- **Mission guards.** Upload and start are refused while a mission is flying,
  from every entry point; the reason says to switch out of AUTO. Upload also
  waits while the arm state is unknown.
- **Missions for surface vehicles.** Rover, boat and sub missions at 0 m or
  below are no longer blocked from upload.
- **Plan drafts.** An unsaved plan is kept as a draft; a plan discarded on
  leaving, replaced through New or Load, or saved just before leaving, no
  longer comes back as a draft.
- **Survey geometry.** Survey passes follow the set bearing, are centred on
  the area and drop zero-length passes.
- **Calibration.** Compass-motor calibration is no longer offered, and ESC
  calibration only on PX4 multirotors. Airspeed calibration works on
  ArduPilot planes that have an airspeed sensor configured, PX4 compass calibration follows the flight controller's
  progress and Cancel stops it on the vehicle, and the reboot prompt appears
  only on ArduPilot.
- **Radio calibration Save** checks the sticks the vehicle actually maps, on
  PX4 and ArduPilot, rovers and boats included (channels 1-4 while PX4 has no
  stick map yet; throttle plus a steering stick on a PX4 rover or boat), and
  refuses with a reason until they have moved or while the stick map is not
  loaded.
- **Parameters.** Closing a parameter-file apply part-way names the value
  whose write was not confirmed; counter and sensor-ID parameters stay
  read-only even when their documentation does not flag them; setup steps
  recover on their own after a reconnect.
- **Logs.** Sharing a log whose file is gone removes it from the list, a failed
  onboard-log download offers Retry and a share of the part that arrived, and a flight record is written once even
  if the first write fails at arm. Skipped import items show their row number,
  and loading the plan the drone reports has its own button label.
- **Disclaimer and licences.** The disclaimer must be accepted before the app
  opens (pair links wait for it), and each licence row opens its bundled text.
- **Language.** The app ships in English only.
- **Home and Quick menu vitals.** A silent flight controller's last mode,
  battery and arm state no longer show as live: while the link is open they
  read unknown and never Ready; after it closes the last reading stays without
  the arm state and says how long ago the vehicle was last heard.
- **Ground station.** Joining a Wi-Fi network on an armed ground station whose
  access point is up asks before standing the access point down. Relayed nodes
  no longer flip to stale between their slower polls. The Retry buttons on the
  ground-station and Visor tabs reload at once, and the agent page's live
  readouts stop polling while the app is in the background.
- **Node pages** mark stale readings and ask before uninstall, restart,
  leaving Wi-Fi, turning the access point off and sharing.
- **Arm state on the flight screen.** The status pill, the vision layers and
  the left column read the arm state only from a fresh pilot frame or a fresh
  heartbeat, so a stale "disarmed" no longer shows pre-flight items or Take off
  while the drone may be flying.
- **Fly Here from the local map** places the target from the flight
  controller's own origin and the drone as drawn, with or without GNSS, and
  the demo drone flies to it, held by the pilot (or back in a pinned mode).
  With neither a fresh pilot nor a fresh heartbeat a long-press says the
  landed state is unknown. Fly Here through the flight controller is
  refused while the pilot reports a flight-controller failsafe.
- **Setup errors** show the shared spec's words for every setup error code;
  a code without spec words, or a stopped setup service, reads as a refusal
  rather than a link failure.

## [0.5.0] - 2026-11-10

This release covers everything since 0.4.0: seventy-one commits, all on
2026-11-10. It is a second pass over the flight screen that 0.4.0 introduced,
with three threads running through it.

The first is one behaviour on every cockpit. The flight-state chip, the status
pill, pilot refusals, the link-loss line and the tips queue are ports held to
the same golden case files as the web cockpits, and every flight-screen word,
confirm hold, take-off limit and Menu order comes from the shared cockpit
spec. A refusal reads "Not ready: Controller" instead of a raw code, and the
link-loss countdown names the configured action.

The second is safety on the flight screen. Pause never cancels a flight
controller failsafe on any route, Disarm waits for a known landing, STOP stays
enabled while disarmed and always asks for the 3 s hold, every controller
binding of the emergency stop waits for that full hold, and every sheet,
dialog and menu opens below the top bar so STOP takes the first press.

The third is first use without interruptions: contextual tips on the ground,
an aircraft setup offer instead of a wizard that opened by itself, and a
notification permission asked only on Home.

### Added

- **Contextual tips.** One-line tips beside their controls on the flight
  screen, one at a time, on the ground and disarmed only, never over STOP, the
  top bar, the left column, the flight-state chip or an alert. A tip hidden for
  a moment by a banner or sheet keeps its turn. Settings has Reset tips.
- **Camera settings sheet.** Camera and stream, picture-in-picture, digital
  zoom and recording over the flight screen. Without a camera that zooms or
  shoots, zoom goes digital (1×, 2×, 4×) and a photo saves the shown frame.
- **Aircraft setup offer.** Offered once per flight controller, on the ground,
  for the selected vehicle while its link is open, with Open and Later.
- **Demo states.** Settings can pin the demo drone to any flight-screen state,
  including a flight controller failsafe; demo layers follow the demo pilot.

### Changed

- **Shared chip and pill.** The chip follows the shared rules case for case:
  hold reasons by their wire names, TAKING OFF with Abort during a launch,
  FAILSAFE first on its second line, a paused mission with Resume, the tuning
  step from the pilot frame, a skill's detail, and the setup button only when
  setup is needed. The pill grades storage on percent used (90 % and 97 %),
  lists the flight controller failsafe and a relayed PreArm text, and orders
  items worst first. The alert list uses the shared severity glyphs.
- **Spec words.** Flight-screen texts, skill reasons, toasts and command
  results read from the cockpit spec strings, as sentences.
- **Top bar.** The link cell shows the age of the newest frame with colour
  bands, the battery shows time to reserve, the controller cell reads "No
  controller" until a pad is seen, and the layer button shows the layers icon
  and the level name.
- **Layout.** The split divider defaults to half within 30 to 70 %; a short
  right column drops the gimbal pad first, then zoom; instrument tapes keep to
  the centre zone, clear of the left column and card; the follow range panel
  no longer repeats AGL.
- **Launch.** Take-off defaults to 3 m within 1 to 120 m, from the spec. Abort
  sends a pause before arming. On-screen sticks count as a controller in the
  pre-flight check where they are shown.
- **Fly Here.** By the flight controller route only in GUIDED; with a stale
  pilot state it sends the point to the flight controller instead of waiting.
- **Emergency stop sheet.** Titled Emergency stop, with one hold button that
  counts down the time still needed. Every controller binding of the
  emergency stop runs as the full 3 s hold.
- **Aircraft setup.** The wizard no longer opens by itself on connect.
  The notification permission is asked only on Home with no vehicle armed.

### Fixed

- **Pause during a failsafe.** Refused on the pilot route and on the routes
  straight to the flight controller (ArduPilot and PX4 mode names).
- **Disarm.** Waits until the drone is known to be on the ground; the landed
  state is requested on connect and again after the flight controller is heard
  after a silence. Betaflight and iNav, which report no landed state, keep the
  confirm only.
- **View switch freeze.** Switching views no longer freezes the flight screen
  with a software-drawn video (the demo drone).
- **Pad chords.** A chord (kill, return home) never also moves or closes a
  sheet, and a hold-to-confirm cannot complete after its button is let go.
- **Stale pilot.** A stalled pilot leaves no countdown, banner or handover on
  screen, and its frozen state never hides flight controller warnings.
- **Mode sheet.** The FC modes section is back.
- **Controller Back.** Closes the overlay drawn on top.

### Notes

- Pairs with ADOS Drone Agent 0.109.101 or later for the shared cockpit spec,
  the pilot failsafe flag and the refusal codes.

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
