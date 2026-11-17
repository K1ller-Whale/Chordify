# Third-party sources

Copied by `tools/vendor_mobile_native.py`; do not edit. Each directory is unmodified upstream
source at the version the Python reference (`chordify_core.features`, run by the server and by
`chordify_ai.export.mobile_goldens`) uses, so the engine computes the same features.

| Directory | Source | Version | Licence |
|---|---|---|---|
| `vamp-plugin-sdk/` | Vamp plugin SDK, from the Python `vamp` 1.1.0 sdist (its `vamp-plugin-sdk/`) | 2.6 | MIT-style, in each file's header |
| `libsoxr/` | libsoxr, from the Python `soxr` 1.1.0 sdist (its `libsoxr/`) | 0.1.3-14-ga66f3ee | LGPL-2.1-or-later (`libsoxr/COPYING.LGPL`, `libsoxr/LICENCE`) |
| `nnls-chroma/` | https://github.com/c4dm/nnls-chroma | commit 4c5f214 | GPL-2.0-or-later (`nnls-chroma/COPYING`) |

`nnls-chroma/chromamethods_nnls.cpp` is generated: `chromamethods.cpp` without
`chordDictionary`, which only Chordino uses and which is the only code that needs Boost.
NNLS Chroma is GPL-2.0-or-later, so the app that bundles it is distributed under the GPL
(the app is GPL-3.0-or-later).
