# Markor Plus — Changelog

This is the changelog for the **Markor Plus** module (separate from the upstream Markor app).  
Format inspired by Keep a Changelog. Semantic versioning is pre-1.0.

## [Unreleased]
### Added
- (Planned) Configurable threshold for “long-title” detection (default 120 chars).
- (Planned) Option for color-blind-friendly icon indicator.
### Changed
- (Planned) Replace stock `CalendarView` with a more customizable third-party calendar.
### Fixed
- (Pending) Minor Folder Tree polish and accessibility tweaks.

---

## [1.2.6] — 2025-08-30
### Added
- **Doc icon badges for long-title notes (>120 chars):**  
  - ○ hollow dot on emoji doc = title-only (no body).  
  - ● filled **red** dot on emoji doc = has body content.
- README and module changelog documenting the Calendar Input + Folder Tree flows.

### Changed
- Date-stamp emphasized at the top of new notes (non-US style).

### Fixed
- Creation-time chronology preserved: editing a note no longer bumps its position.

### Performance
- Image-import optimization (limited to 1024px) groundwork (candidate for separate upstream PR; benchmarks to follow).

### Known limitations
- Rename/move/copy is handled via Markor’s native browser.
- Very large archives may still impact scanning; `.nomedia` or `.skip-scan` can mitigate.
- Root paths vary across devices; no automated migration yet.
- Accessibility/i18n and small-screen polish ongoing.
- **CalendarView quirk:** browsing to other months in the Folder Tree reflects correctly **only after a day is selected** in the calendar (stock `CalendarView` limitation). Future updates will evaluate third-party calendars to remove this quirk.

### Known issues
- Intermittent incorrect **datestamp** on new notes. 
  **Symptoms:** date can be off by ±1 day or time not matching exact local time.  
  **Workaround:** tap/select Reset, then create the note.  
  **Scope:** likely CalendarView selection ↔ system clock combine edge case.  
 


---

## [1.2.5] — 2025-08-14
### Added
- Category management (prototype): add/delete categories in-app.

### Fixed
- General stability and UI polish in Calendar Input and Folder Tree.

---

## [1.2.4] — 2025-08-11
### Changed
- UI polish pass; improved contrasts and minor layout tweaks.

---

## [1.2.3] — 2025-08-08
### Added
- Early category editor hooks and Folder Tree expansions by default path.

---

## [1.2.2] — 2025-08-04
### Fixed
- Creation-order vs “date modified” conflict resolved; stable ordering guaranteed.

---

## [1.2.1] — 2025-08-01
### Added
- Title-only notes supported (long titles up to ~250 characters).

---

## [1.2.0] — 2025-07-31
### Added
- Initial prototype: Calendar Input (FAB) and Folder Tree (Year → Month → Category → Notes).
- Auto-creation of missing Year/Month/Category on submit.
- Local-first storage under a configurable root directory.
