# PDGA Compare

Android app for keeping score in disc golf. After a round it estimates the PDGA round rating
your score would have got, based on real PDGA results on the same layout.

## How the rating estimate works

1. A layout is linked to a PDGA layout on the same course. The app reads the results (score and
   round rating of every player) of PDGA events played on it.
2. For each of the **5 most recent** PDGA rounds on the layout, it takes the results on and
   around your score (±2 throws, widened until there are at least two different scores) and
   fits a straight line through them. Within one PDGA round, rating is linear in score, so the
   line gives the rating your score would have got in that round.
3. The estimate is the average of those 5 rounds. "Show details" on the scorecard lists every
   round used.

## Using the app

- **Add layout → Find course**: search by course name (country DK by default). Pick your layout
  from the Disc Golf Metrix list; holes and pars are read from its Metrix page.
- The app then searches PDGA events in the country over the last 4 years, by the course's name
  and town, and reads the newest ones. PDGA layouts count as the same when course, holes and par
  match and lengths are within 5% (tournament directors name the same tees differently per event).
- If the course has one PDGA layout it is used directly; otherwise you pick it, with the best match
  (holes, par, tee colour, length) on top. **Change PDGA layout** on the layout page switches, e.g.
  from white to yellow tees. **Add by link** takes PDGA event links directly.
- **Start new round**: pick layout and players (any number, no PDGA number needed), then score
  hole by hole. The summary shows each player's estimated rating.

## Building

Every push builds a debug APK in GitHub Actions (artifact `pdgacompare-apk`). Download it,
unzip, and install the APK on the phone (allow installing from unknown sources). All builds are
signed with the same key (`app/debug.keystore`), so new versions install over old ones.

Locally: `./gradlew :core:test :app:assembleDebug` (needs the Android SDK).
The pure-Kotlin logic in `core/` can be tested without Android: `gradle -p core test`.

## Data sources

- **Disc Golf Metrix**: course list (`api.php?content=courses_list`) and public course pages for hole pars.
- **PDGA**: event search (`www.pdga.com/tour/search`) and event results pages
  (`www.pdga.com/tour/event/ID`). PDGA has no open API for this, so site changes can break parsing
  (`core/.../PdgaParser.kt`). `LIVE=1 gradle -p core test --tests '*LiveSmokeTest*' -i` runs the
  whole search against the real sites.
- **UDisc** has no public API and is not used.
