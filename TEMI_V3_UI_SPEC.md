# Kirjastobotti — Temi V3 UI specification

This is the visual and interaction brief for the custom library-catalogue UI.
It keeps the existing Kirjastobotti character: calm dark surfaces, an
uncluttered layout, bright blue action accents, large rounded controls, and a
friendly robot-guidance flow. It is a UI **on top of** a library connector,
not a reskin of a particular library website.

## Product promise

A visitor should be able to walk up, type or say a book title, understand
whether the book is available in this branch, and ask the robot to take them
to its shelf—without needing library-system knowledge or a login.

The interface must work for every configured catalogue provider. A provider
may supply a catalogue URL, API, or fallback web-search link; it must not
change the screens or vocabulary below.

## Device canvas and touch rules

Temi V3 has a 13.3-inch, 1920 × 1080 landscape touch display. Design at that
canvas size with a 48 px safe inset on every edge (64 px for persistent
controls). Do not put important actions at the extreme corners, where the
robot's tilted display is harder to reach.

| Element | Rule |
| --- | --- |
| Minimum touch target | 72 × 72 px; 88 px for the primary action |
| Standard button height | 88 px |
| Page title | 42–48 sp, semibold |
| Body text | 26–30 sp; never below 22 sp |
| Card corner radius | 24 px |
| Spacing rhythm | 16 / 24 / 32 / 48 px |
| Results per view | 3 large cards maximum; scroll for more |

Use the screen in landscape only. Every important operation must have a
visible touch control; speech is an optional shortcut, never the sole route.

## Visual language

The existing app's palette is the base. Use blue only to express an available
next action; do not use colour alone to communicate status.

| Token | Value | Use |
| --- | --- | --- |
| `surface` | `#101820` | page background and dark cards |
| `surface-raised` | `#26343D` | selected or elevated cards |
| `text-primary` | `#F7F9FB` | titles and key information |
| `text-secondary` | `#B8C4CC` | supporting text |
| `action` | `#1976A8` | primary buttons and focus rings |
| `action-soft` | `#8DD8FF` | icon highlights and illustrations |
| `success` | `#2E9D63` | available status, with text/icon |
| `warning` | `#D8942B` | limited/unknown status, with text/icon |
| `danger` | `#D95555` | errors only |

Use a humanist sans-serif already available on Android (Roboto is fine).
Avoid thin text, glossy gradients, all-caps labels, tiny icon-only controls,
and auto-advancing content. A simple book-cover image is optional decoration;
the title and availability must remain complete without it.

## Global frame

```text
┌──────────────────────────────────────────────────────────────────────────┐
│  [Kirjastobotti mark]   Library name                         [FI] [?]   │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│                         Screen-specific content                         │
│                                                                          │
├──────────────────────────────────────────────────────────────────────────┤
│  [⌂ Home]             Spoken guidance / clear status        [← Back]   │
└──────────────────────────────────────────────────────────────────────────┘
```

- The header identifies the selected library, not its catalogue vendor.
- `?` opens a short, plain-language help panel with “How to search” and
  “Ask staff for help”.
- The language control shows the current language and opens a list with the
  language names written in their own language (for example `Suomi`,
  `English`, `Svenska`). Start with Finnish, English and Swedish.
- Home and Back always retain both icon and text labels.
- Do not hide system state in a toast. Show it in the persistent guidance
  area and announce important changes through TalkBack.

## 1. Home / search screen

This is the idle-friendly landing screen. It should feel like the current
robot experience: direct, warm, and focused on one task.

```text
                     Find a book
          Search this library's collection

  ┌──────────────────────────────────────────────────────────┐  ┌───────┐
  │  🔎  Title, author, subject, or ISBN                       │  │ Speak │
  └──────────────────────────────────────────────────────────┘  └───────┘

          [ Browse popular topics ]       [ Scan a book ISBN ]

  You can ask me to take you to a shelf after you find a book.
```

- Autofocus the search field only after a visitor touches the screen, so the
  on-screen keyboard does not block the welcome state.
- The `Speak` button starts listening and visibly offers `Stop listening`.
  It must show the recognized words before searching.
- Provide a large Finnish virtual keyboard when the system keyboard is not
  appropriate. Search begins with an explicit `Search` key/button.
- Empty-state example: “Try a title, an author, or a topic such as history.”

## 2. Search results

```text
Search: [ Tove Jansson                                      ] [Search]
12 results                                      Sort: [Best match ▾]

┌───────────┬─────────────────────────────────────────────────────────────┐
│ [cover]   │  Muumipappa ja meri                                           │
│           │  Tove Jansson · Book · Finnish                                │
│           │  ✓ Available here · Shelf 84.2 JAN                            │
│           │                                      [View book] [Take me]    │
└───────────┴─────────────────────────────────────────────────────────────┘
┌───────────┬─────────────────────────────────────────────────────────────┐
│ [cover]   │  ...                                                          │
└───────────┴─────────────────────────────────────────────────────────────┘
```

- Each card is fully tappable to open details; the explicit buttons remain
  for clarity.
- `Take me` appears only when a usable shelf/location is known. When it is
  not available, use `View availability`, never a disabled unexplained
  button.
- Availability states use an icon, words, and colour: `Available here`,
  `On loan`, `Available at another branch`, or `Check with staff`.
- Keep author, format, language, branch and call number in a predictable
  order. Do not make visitors decode catalogue abbreviations.

## 3. Book details

```text
                         Muumipappa ja meri
                 Tove Jansson · Book · Finnish

  ┌──────── Availability at Central Library ─────────────────────────────┐
  │  ✓ Available now                                                     │
  │  Shelf: 84.2 JAN                    [ Take me to this shelf ]        │
  └──────────────────────────────────────────────────────────────────────┘

  About this book  •  Other editions  •  More availability
```

- Make the local branch availability the first thing on the screen. Other
  branches are secondary and collapsed by default.
- Reservation, account, payment, and personal data belong to the library's
  official flow. Open that flow clearly in a separate, labelled step rather
  than collecting credentials in Kirjastobotti.
- If there are several local copies on different shelves, show each as a
  separate large action: `Children's section — 84.2 JAN`.

## 4. Navigation confirmation and guidance

```text
                    Ready to guide you
                Muumipappa ja meri
                 Shelf 84.2 JAN

                 [ Start guidance ]
                    [ Not now ]
```

During guidance, replace catalogue controls with one large, low-distraction
status card: destination, a simple direction/progress illustration, and
`Stop guidance`. Announce arrival in speech and on screen: “We have arrived
at shelf 84.2 JAN.” Offer `Search another book` and `Return home`.

If navigation fails, say what happened and give a recoverable choice:
“I cannot reach this shelf right now. You can try again or ask library
staff.” Never blame the visitor or expose a technical error code.

## Accessibility acceptance criteria

- Meet WCAG 2.2 AA contrast: normal text at least 4.5:1 and large text at
  least 3:1. Verify actual rendered colours, including pressed/disabled
  states.
- All flows work with touch, TalkBack and an external keyboard/D-pad.
- Focus order follows the visual order; a visible 4 px high-contrast focus
  ring is present on every interactive element.
- Every icon has a text label or an accessible name. Decorative cover art is
  excluded from the accessibility tree.
- Do not use time limits; after 90 seconds of inactivity, show a gentle
  “Still looking for a book?” prompt with `Continue` and `Home`.
- Plain language at approximately CEFR A2–B1 level. Avoid library jargon;
  use `Shelf` alongside any required call number.
- Support dynamic text sizing without clipping. At 200% font scale, switch
  result cards to a one-column detail layout rather than shrinking text.
- Ensure no essential information relies on colour, sound, speech, motion,
  or a book-cover image.

## Catalogue-provider contract for the UI

The UI consumes normalized data, so an Outi/Finna implementation and a
different library system render identically.

```kotlin
data class BookResult(
    val id: String,
    val title: String,
    val author: String?,
    val format: String?,
    val language: String?,
    val coverUrl: String?,
    val localAvailability: Availability,
    val locations: List<BranchLocation>,
    val officialDetailsUrl: String?
)

data class BranchLocation(
    val branchName: String,
    val status: Availability,
    val shelfCode: String?,
    val navigationDestinationId: String?
)
```

The UI must gracefully handle missing cover art, no shelf code, a temporary
provider error, offline mode, and a result that only has a link to the
official catalogue. Those cases change the available actions—not the visual
design or basic search experience.

## Definition of done

- A visitor can complete search → local availability → shelf guidance in
  three or fewer deliberate taps after entering a search.
- The same UI passes with Finna data and with a mock non-Finna provider.
- Usability is tested with at least one screen-reader user and visitors with
  different language, mobility, vision, and digital-skill needs.
- The design is reviewed on the physical Temi V3 at normal standing distance,
  including a tilted-screen position and bright library lighting.
