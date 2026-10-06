# PDGA Compare

Android app for keeping score in disc golf. After a round it estimates the PDGA round rating
your score would have got, based on real PDGA results on the same layout.

## How the rating estimate works

1. A layout is linked to one or more PDGA events played on it. The app reads each event's
   results (score and round rating of every player, all divisions combined) per round and layout.
2. For each of the **5 most recent** PDGA rounds on the layout, it takes the results on and
   around your score (±2 throws, widened until there are at least two different scores) and
   fits a straight line through them. Within one PDGA round, rating is linear in score, so the
   line gives the rating your score would have got in that round.
3. The estimate is the average of those 5 rounds. "Show details" on the scorecard lists every
   round used.

Rounds on a PDGA layout with a different number of holes are ignored.

## Using the app

- **Add layout → From a PDGA event**: paste a PDGA event link (`pdga.com/tour/event/12345`) and
  pick the layout you play. Hole pars come from PDGA when available; otherwise check them.
- On the layout page, **Add events** to add more PDGA events. Rounds on the same PDGA layout are
  picked up automatically; if the event named the layout differently, you are asked which one it is.
  **Refresh** re-reads the events (unofficial ratings become official after a while).
- Layouts can also be imported from Disc Golf Metrix (holes, par, lengths) or created manually.
- **Start new round**: pick layout and players (any number, no PDGA number needed), then score
  hole by hole. The summary shows each player's estimated rating.

## Building

Every push builds a debug APK in GitHub Actions (artifact `pdgacompare-apk`). Download it,
unzip, and install the APK on the phone (allow installing from unknown sources). All builds are
signed with the same key (`app/debug.keystore`), so new versions install over old ones.

Locally: `./gradlew :core:test :app:assembleDebug` (needs the Android SDK).
The pure-Kotlin logic in `core/` can be tested without Android: `gradle -p core test`.

## Data sources

- **PDGA**: event results pages (`www.pdga.com/tour/event/ID`), falling back to PDGA Live data.
  PDGA has no open API for this, so changes to their site can break parsing (`core/.../PdgaParser.kt`).
- **Disc Golf Metrix**: `discgolfmetrix.com/api.php` (some installations require an API code).
- **UDisc** has no public API and is not used.
