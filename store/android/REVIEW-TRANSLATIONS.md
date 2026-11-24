# Translations waiting for native review

These were translated without a native speaker. They need review by one
before release in the country named. The terms follow Ortus core's web
catalogs (`web/src/locales/es.ts`, `it.ts`), which wait for the same review.

| Language | Files | Status |
|---|---|---|
| Spanish (es) | `app/src/main/res/values-es/strings.xml`, `store/android/listing/es/` | Translated without a native speaker; needs review before release in Spain |
| Italian (it) | `app/src/main/res/values-it/strings.xml`, `store/android/listing/it/` | Translated without a native speaker; needs review before release in Italy |

ortus-ios has the same texts in its es and it catalogs
(`Ortus/Resources/{es,it}.lproj`, waiting for the same review), and
`tools/strings.py` lists both languages. A reviewed wording goes into
ortus-ios first, then here.

When a native speaker has reviewed a language, remove its row and the note
at the top of its strings.xml.
